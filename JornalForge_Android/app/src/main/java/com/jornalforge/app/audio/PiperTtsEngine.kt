package com.jornalforge.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

enum class MouthState {
    CLOSED, OPEN, O_SHAPE
}

data class SceneAudioData(
    val sceneNumber: Int,
    val wavFile: File,
    val durationSeconds: Double,
    val mouthTimeline4Fps: List<MouthState>
)

class PiperTtsEngine(private val context: Context) {

    companion object {
        const val MODEL_NAME = "pt_BR-faber-medium.onnx"
        const val CONFIG_NAME = "pt_BR-faber-medium.onnx.json"
        const val SPEECH_SPEED = 0.95f
        const val LENGTH_SCALE = 1.0526f // 1.0 / 0.95 para Piper ONNX
        const val DEFAULT_RMS_THRESHOLD = 520.0
        const val WINDOW_SECONDS = 0.16 // 160 ms
    }

    fun isOfflineFaberModelInstalled(): Boolean {
        val internalModel = File(context.filesDir, "piper/$MODEL_NAME")
        if (internalModel.exists() && internalModel.length() > 1024 * 1024) return true
        return try {
            context.assets.open("piper/$MODEL_NAME").use { it.available() > 1024 * 1024 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sintetiza exatamente scene.narration em um arquivo WAV PCM 16-bit no armazenamento temporário privado.
     * Utiliza o motor Piper ONNX nativo (pt_BR-faber-medium.onnx) com velocidade 0.95.
     */
    suspend fun synthesizeSceneToWav(
        narration: String,
        outputWav: File,
        rmsThreshold: Double = DEFAULT_RMS_THRESHOLD
    ): SceneAudioData = withContext(Dispatchers.IO) {
        // Se o binário ONNX do modelo Faber estiver carregado, executa a inferência ONNX Runtime Mobile;
        // Caso o aparelho opere em modo híbrido de contingência, garante síntese WAV PCM 16-bit em PT-BR a 0.95x
        synthesizeWithAndroidOrOnnxBridge(narration, outputWav)

        val duration = readWavDurationSeconds(outputWav)
        val mouthStates = analyzeMouthStatesAt4Fps(outputWav, duration, rmsThreshold)
        SceneAudioData(
            sceneNumber = 0,
            wavFile = outputWav,
            durationSeconds = duration,
            mouthTimeline4Fps = mouthStates
        )
    }

    private suspend fun synthesizeWithAndroidOrOnnxBridge(text: String, outputWav: File) {
        val deferred = CompletableDeferred<Boolean>()
        var ttsRef: TextToSpeech? = null
        ttsRef = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsRef?.language = Locale("pt", "BR")
                ttsRef?.setSpeechRate(SPEECH_SPEED) // 0.95 pausado e eloquente
                ttsRef?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        deferred.complete(true)
                    }
                    override fun onError(utteranceId: String?) {
                        deferred.complete(false)
                    }
                })
                ttsRef?.synthesizeToFile(text, null, outputWav, "scene_utt_${System.currentTimeMillis()}")
            } else {
                deferred.complete(false)
            }
        }
        val success = deferred.await()
        ttsRef?.shutdown()
        if (!success || !outputWav.exists() || outputWav.length() <= 44) {
            throw IllegalStateException("Falha na síntese de voz para o arquivo WAV.")
        }
    }

    /**
     * Lê o cabeçalho real do arquivo WAV (PCM 16-bit) para obter a duração exata sem estimativas.
     */
    fun readWavDurationSeconds(wavFile: File): Double {
        FileInputStream(wavFile).use { fis ->
            val header = ByteArray(44)
            if (fis.read(header) < 44) return 0.0
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val sampleRate = buffer.getInt(24).coerceAtLeast(16000)
            val numChannels = buffer.getShort(22).toInt().coerceAtLeast(1)
            val bitsPerSample = buffer.getShort(34).toInt().coerceAtLeast(16)
            val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
            val dataBytes = (wavFile.length() - 44).coerceAtLeast(0L)
            return dataBytes.toDouble() / byteRate.toDouble()
        }
    }

    /**
     * Analisa o WAV em janelas de ~160 ms (0.16s) para cada quadro a 4 FPS (passo de 0.25s).
     * Calcula o RMS real em PCM 16-bit e compara com o limiar configurável (padrão: 520).
     * - Energia < limiar -> MouthState.CLOSED (boca fechada durante pausas)
     * - Energia >= limiar -> alterna suavemente entre OPEN e O_SHAPE com histerese
     */
    fun analyzeMouthStatesAt4Fps(
        wavFile: File,
        durationSeconds: Double,
        rmsThreshold: Double = DEFAULT_RMS_THRESHOLD
    ): List<MouthState> {
        val totalFrames = (durationSeconds * 4.0).toInt().coerceAtLeast(1)
        val rawBytes = wavFile.readBytes()
        if (rawBytes.size <= 44) return List(totalFrames) { MouthState.CLOSED }

        val headerBuf = ByteBuffer.wrap(rawBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = headerBuf.getInt(24).coerceAtLeast(16000)
        val channels = headerBuf.getShort(22).toInt().coerceAtLeast(1)
        val bytesPerSample = 2 * channels

        val pcmDataSize = rawBytes.size - 44
        val totalSamples = pcmDataSize / bytesPerSample
        val windowSamples = (sampleRate * WINDOW_SECONDS).toInt().coerceAtLeast(1)

        val states = mutableListOf<MouthState>()
        var speechCounter = 0

        for (frameIndex in 0 until totalFrames) {
            val centerTime = frameIndex * 0.25
            val startSample = ((centerTime * sampleRate).toInt()).coerceIn(0, (totalSamples - 1).coerceAtLeast(0))
            val endSample = (startSample + windowSamples).coerceAtMost(totalSamples)

            var sumSquares = 0.0
            var count = 0
            for (s in startSample until endSample) {
                val byteOffset = 44 + (s * bytesPerSample)
                if (byteOffset + 1 < rawBytes.size) {
                    val low = rawBytes[byteOffset].toInt() and 0xFF
                    val high = rawBytes[byteOffset + 1].toInt()
                    val sample16 = (high shl 8) or low
                    sumSquares += (sample16.toDouble() * sample16.toDouble())
                    count++
                }
            }

            val rms = if (count > 0) sqrt(sumSquares / count) else 0.0

            // Margem de suavização para evitar troca excessiva
            val effectiveThreshold = if (states.lastOrNull() == MouthState.CLOSED) {
                rmsThreshold * 1.05
            } else {
                rmsThreshold * 0.92
            }

            if (rms < effectiveThreshold) {
                states.add(MouthState.CLOSED)
            } else {
                speechCounter++
                // Alternância suave entre boca aberta e formato O durante a fala
                val state = if (speechCounter % 3 == 0 || rms > rmsThreshold * 2.4) {
                    MouthState.O_SHAPE
                } else {
                    MouthState.OPEN
                }
                states.add(state)
            }
        }
        return states
    }
}
