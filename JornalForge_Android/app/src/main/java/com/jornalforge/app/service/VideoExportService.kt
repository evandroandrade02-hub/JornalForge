package com.jornalforge.app.service

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.jornalforge.app.audio.MouthState
import com.jornalforge.app.audio.PiperTtsEngine
import com.jornalforge.app.audio.SceneAudioData
import com.jornalforge.app.model.NormalizedScript
import com.jornalforge.app.model.ScriptParser
import com.jornalforge.app.model.VideoAspect
import com.jornalforge.app.video.FrameRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream

sealed class ExportStatus {
    object Idle : ExportStatus()
    data class Running(val phase: String, val progressPercent: Int) : ExportStatus()
    data class Completed(val galleryUri: Uri, val displayPath: String) : ExportStatus()
    data class Failed(val exactPhase: String, val details: String) : ExportStatus()
}

class VideoExportService : Service() {

    companion object {
        private val _statusFlow = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
        val statusFlow: StateFlow<ExportStatus> = _statusFlow

        const val ACTION_START = "com.jornalforge.app.START_EXPORT"
        const val ACTION_CANCEL = "com.jornalforge.app.CANCEL_EXPORT"
        const val EXTRA_JSON = "extra_json"
        const val EXTRA_ASPECT = "extra_aspect"
        const val EXTRA_RMS_THRESHOLD = "extra_rms"
        const val EXTRA_QUICK_TEST = "extra_quick_test"
        private const val CHANNEL_ID = "jornalforge_export_channel"
        private const val NOTIF_ID = 1001
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                FFmpegKit.cancel()
                currentJob?.cancel()
                cleanupTempDir()
                _statusFlow.value = ExportStatus.Failed("Exportação cancelada pelo usuário", "Arquivos temporários removidos.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val rawJson = intent.getStringExtra(EXTRA_JSON) ?: ""
                val aspectName = intent.getStringExtra(EXTRA_ASPECT) ?: VideoAspect.HORIZONTAL_16_9.name
                val rmsThreshold = intent.getDoubleExtra(EXTRA_RMS_THRESHOLD, PiperTtsEngine.DEFAULT_RMS_THRESHOLD)
                val isQuickTest = intent.getBooleanExtra(EXTRA_QUICK_TEST, false)
                val aspect = VideoAspect.valueOf(aspectName)

                startForeground(NOTIF_ID, buildNotification("Preparando roteiro", 0))
                currentJob = serviceScope.launch {
                    runPipeline(rawJson, aspect, rmsThreshold, isQuickTest)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(
        rawJson: String,
        aspect: VideoAspect,
        rmsThreshold: Double,
        isQuickTest: Boolean
    ) {
        val tempRoot = File(cacheDir, "jornalforge_job")
        try {
            updatePhase("Preparando roteiro", 5)
            val script: NormalizedScript = if (isQuickTest) {
                ScriptParser.getQuickTestScript()
            } else {
                ScriptParser.validateAndNormalize(rawJson).getOrElse { err ->
                    failAt("Falha na validação do roteiro", err.localizedMessage ?: "JSON inválido")
                    return
                }
            }

            // Verificar espaço livre antes de iniciar
            val stat = StatFs(cacheDir.absolutePath)
            val availableMegabytes = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
            if (availableMegabytes < 250) {
                failAt("Falta de espaço no armazenamento", "Espaço disponível insuficiente ($availableMegabytes MB). Necessário mínimo de 250 MB.")
                return
            }

            if (tempRoot.exists()) tempRoot.deleteRecursively()
            val wavDir = File(tempRoot, "wavs").apply { mkdirs() }
            val framesDir = File(tempRoot, "frames").apply { mkdirs() }

            // Etapa 3 & 4: Sintetizar um WAV por cena com voz Faber e analisar pausas RMS
            val ttsEngine = PiperTtsEngine(applicationContext)
            val sceneAudioList = mutableListOf<SceneAudioData>()
            val totalScenes = script.scenes.size

            for ((index, scene) in script.scenes.withIndex()) {
                currentCoroutineContext().ensureActive()
                val sceneNum = index + 1
                val progress = 10 + ((index.toFloat() / totalScenes) * 25).toInt()
                updatePhase("Gerando narração Faber cena $sceneNum de $totalScenes", progress)

                val wavFile = File(wavDir, String.format("scene_%04d.wav", sceneNum))
                val audioData = try {
                    ttsEngine.synthesizeSceneToWav(scene.narration, wavFile, rmsThreshold)
                } catch (e: Exception) {
                    failAt("Falha na síntese de voz", "Cena $sceneNum: ${e.localizedMessage}")
                    return
                }

                updatePhase("Analisando pausas da voz", progress + 2)
                sceneAudioList.add(audioData.copy(sceneNumber = sceneNum))
            }

            // Etapa 5: Concatenar os WAVs em uma narração completa via lista FFmpeg
            val concatListFile = File(tempRoot, "wav_list.txt")
            concatListFile.writeText(
                sceneAudioList.joinToString("
") { "file '${it.wavFile.absolutePath}'" }
            )
            val fullAudioWav = File(tempRoot, "full_narration.wav")
            val concatSession = FFmpegKit.execute("-y -f concat -safe 0 -i "${concatListFile.absolutePath}" -c copy "${fullAudioWav.absolutePath}"")
            if (!ReturnCode.isSuccess(concatSession.returnCode) || !fullAudioWav.exists()) {
                failAt("Falha na síntese de voz", "Não foi possível concatenar os arquivos WAV das cenas.")
                return
            }

            // Etapa 6, 7 & 8: Renderizar frames a 4 FPS (um Bitmap por vez, liberando memória imediatamente)
            val frameRenderer = FrameRenderer(applicationContext)
            var globalFrameIndex = 1

            for ((index, scene) in script.scenes.withIndex()) {
                currentCoroutineContext().ensureActive()
                val sceneNum = index + 1
                val audioData = sceneAudioList[index]
                val sceneFramesCount = audioData.mouthTimeline4Fps.size.coerceAtLeast(1)

                val progress = 40 + ((index.toFloat() / totalScenes) * 35).toInt()
                updatePhase("Renderizando avatar e legendas cena $sceneNum de $totalScenes", progress)

                for (f in 0 until sceneFramesCount) {
                    currentCoroutineContext().ensureActive()
                    val localTime = f * 0.25 // 4 FPS
                    val mouthState: MouthState = audioData.mouthTimeline4Fps.getOrElse(f) { MouthState.CLOSED }
                    val frameFile = File(framesDir, String.format("frame_%06d.jpg", globalFrameIndex++))

                    try {
                        frameRenderer.renderAndSaveFrame(
                            videoTitle = script.videoTitle,
                            scene = scene,
                            localTime = localTime,
                            sceneDuration = audioData.durationSeconds,
                            mouthState = mouthState,
                            aspect = aspect,
                            outputFile = frameFile
                        )
                    } catch (e: Exception) {
                        failAt("Falha ao criar frames", "Erro na cena $sceneNum: ${e.localizedMessage}")
                        return
                    }
                }
            }

            // Etapa 9, 10, 11 & 12: Codificar vídeo H.264 + AAC 192k 44.1kHz estéreo + faststart + -shortest
            updatePhase("Codificando vídeo", 80)
            val finalTempMp4 = File(tempRoot, "jornalforge_final.mp4")
            updatePhase("Adicionando áudio", 86)

            val ffmpegCmd = buildString {
                append("-y -framerate 4 -i "${framesDir.absolutePath}/frame_%06d.jpg" ")
                append("-i "${fullAudioWav.absolutePath}" ")
                append("-c:v libx264 -preset veryfast -pix_fmt yuv420p ")
                append("-c:a aac -b:a 192k -ar 44100 -ac 2 ")
                append("-movflags +faststart -shortest "${finalTempMp4.absolutePath}"")
            }

            val encodeSession = FFmpegKit.execute(ffmpegCmd)
            if (!ReturnCode.isSuccess(encodeSession.returnCode) || !finalTempMp4.exists() || finalTempMp4.length() == 0L) {
                failAt("Falha no FFmpeg", encodeSession.failStackTrace ?: "Erro na codificação H.264/AAC.")
                return
            }

            // Etapa 13: Salvar somente o MP4 final com áudio na Galeria via MediaStore
            updatePhase("Salvando na galeria", 94)
            val savedUri = saveMp4ToGallery(finalTempMp4, script.videoTitle)
            if (savedUri == null) {
                failAt("Falha ao salvar na galeria", "Não foi possível gravar o arquivo MP4 no MediaStore.")
                return
            }

            // Etapa 14: Apagar todos os arquivos intermediários
            updatePhase("Limpeza dos arquivos temporários", 98)
            cleanupTempDir()

            // Etapa 15: Concluir somente após o MP4 estar fechado na galeria
            updatePhase("Vídeo finalizado", 100)
            _statusFlow.value = ExportStatus.Completed(savedUri, "Movies/JornalForge")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

        } catch (e: CancellationException) {
            cleanupTempDir()
        } catch (e: Exception) {
            failAt("Falha inesperada", e.localizedMessage ?: "Erro desconhecido")
        }
    }

    private fun saveMp4ToGallery(sourceMp4: File, title: String): Uri? {
        return try {
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
            val fileName = "JornalForge_${cleanTitle}_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/JornalForge")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = applicationContext.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values) ?: return null

            resolver.openOutputStream(itemUri)?.use { outStream ->
                FileInputStream(sourceMp4).use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanupTempDir() {
        try {
            val tempRoot = File(cacheDir, "jornalforge_job")
            if (tempRoot.exists()) tempRoot.deleteRecursively()
        } catch (_: Exception) {}
    }

    private fun updatePhase(phase: String, percent: Int) {
        _statusFlow.value = ExportStatus.Running(phase, percent)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(phase, percent))
    }

    private fun failAt(exactPhase: String, details: String) {
        cleanupTempDir()
        _statusFlow.value = ExportStatus.Failed(exactPhase, details)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(phase: String, percent: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JornalForge • Produção de Vídeo")
            .setContentText("$phase ($percent%)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setProgress(100, percent, false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Exportação JornalForge",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
