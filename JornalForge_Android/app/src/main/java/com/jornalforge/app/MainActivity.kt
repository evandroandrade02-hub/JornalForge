package com.jornalforge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jornalforge.app.audio.PiperTtsEngine
import com.jornalforge.app.model.ScriptParser
import com.jornalforge.app.model.VideoAspect
import com.jornalforge.app.service.ExportStatus
import com.jornalforge.app.service.VideoExportService
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD92323),
                    onPrimary = Color.White,
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF151A24),
                    onSurface = Color(0xFFECEFF4)
                )
            ) {
                JornalForgeScreen()
            }
        }
    }
}

@Composable
fun JornalForgeScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val exportStatus by VideoExportService.statusFlow.collectAsState()

    // Etapa 1 States
    var themeText by remember { mutableStateOf("") }
    var selectedDuration by remember { mutableStateOf(6) }
    var sourceBaseText by remember { mutableStateOf("") }

    // Etapa 2 States
    var jsonInput by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf("Aguardando colagem ou importação do JSON.") }
    var isJsonValid by remember { mutableStateOf(false) }

    // Etapa 3 & 4 States
    var selectedAspect by remember { mutableStateOf(VideoAspect.HORIZONTAL_16_9) }
    var rmsThreshold by remember { mutableStateOf(520f) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val content = BufferedReader(InputStreamReader(stream)).readText()
                    jsonInput = content
                    val res = ScriptParser.validateAndNormalize(content)
                    res.onSuccess { script ->
                        isJsonValid = true
                        validationMessage = "JSON Válido! Título: '${script.videoTitle}' | ${script.scenes.size} cenas normalizadas."
                    }.onFailure { err ->
                        isJsonValid = false
                        validationMessage = "Erro: ${err.localizedMessage}"
                    }
                }
            } catch (e: Exception) {
                validationMessage = "Erro ao ler arquivo: ${e.localizedMessage}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabeçalho JornalForge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(34.dp)
                    .background(Color(0xFFD92323))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "JORNALFORGE ANDROID",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Gerador Jornalístico • Voz Faber PT-BR • Exportação MP4 Única",
                    color = Color(0xFF9AA5B8),
                    fontSize = 12.sp
                )
            }
        }

        // ETAPA 1 - Fonte e Configuração
        StepCard(title = "ETAPA 1 • Fonte e Configuração") {
            OutlinedTextField(
                value = themeText,
                onValueChange = { themeText = it },
                label = { Text("Tema (Ex: O verdadeiro interesse por trás de uma decisão)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Duração aproximada:", color = Color(0xFFB8C2D4), fontSize = 13.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(4, 6, 8, 10).forEach { min ->
                    FilterChip(
                        selected = selectedDuration == min,
                        onClick = { selectedDuration = min },
                        label = { Text("$min min") }
                    )
                }
            }

            OutlinedTextField(
                value = sourceBaseText,
                onValueChange = { sourceBaseText = it },
                label = { Text("Fonte-base (Artigo, transcrição, resumo ou contexto)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            )

            if (sourceBaseText.trim().startsWith("http://") || sourceBaseText.trim().startsWith("https://")) {
                Text(
                    text = "⚠️ Você colou um link. Para máxima fidelidade jornalística, forneça também a transcrição ou resumo do conteúdo-base.",
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (themeText.isBlank() || sourceBaseText.isBlank()) {
                        Toast.makeText(context, "Preencha o tema e a fonte-base.", Toast.LENGTH_SHORT).show()
                    } else {
                        val prompt = ScriptParser.buildJournalisticPrompt(themeText, selectedDuration, sourceBaseText)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Prompt JornalForge", prompt))
                        Toast.makeText(context, "Prompt jornalístico gerado e copiado!", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD92323))
            ) {
                Text("Gerar e copiar prompt", fontWeight = FontWeight.Bold)
            }
        }

        // ETAPA 2 - Importação e Validação
        StepCard(title = "ETAPA 2 • Importação e Validação do JSON") {
            OutlinedTextField(
                value = jsonInput,
                onValueChange = {
                    jsonInput = it
                    isJsonValid = false
                },
                label = { Text("Cole aqui o roteiro JSON retornado pela IA") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (clipText.isNotEmpty()) {
                            jsonInput = clipText
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Colar Área Transf.", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Importar .json/.txt", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val result = ScriptParser.validateAndNormalize(jsonInput)
                    result.onSuccess { script ->
                        isJsonValid = true
                        validationMessage = "✅ Estrutura válida! Título: "${script.videoTitle}" | ${script.scenes.size} cenas prontas."
                    }.onFailure { err ->
                        isJsonValid = false
                        validationMessage = "❌ ${err.localizedMessage}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Validar estrutura", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = validationMessage,
                color = if (isJsonValid) Color(0xFF66BB6A) else Color(0xFFFFCA28),
                fontSize = 13.sp
            )
        }

        // ETAPA 3 - Vídeo Único
        StepCard(title = "ETAPA 3 • Formato e Geração de Vídeo Único") {
            VideoAspect.values().forEach { aspect ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = selectedAspect == aspect,
                        onClick = { selectedAspect = aspect }
                    )
                    Text(text = aspect.label, color = Color.White, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        val validation = ScriptParser.validateAndNormalize(jsonInput)
                        if (validation.isFailure) {
                            validationMessage = "❌ Valide um JSON correto antes de gerar: ${validation.exceptionOrNull()?.localizedMessage}"
                            return@Button
                        }
                        val intent = Intent(context, VideoExportService::class.java).apply {
                            action = VideoExportService.ACTION_START
                            putExtra(VideoExportService.EXTRA_JSON, jsonInput)
                            putExtra(VideoExportService.EXTRA_ASPECT, selectedAspect.name)
                            putExtra(VideoExportService.EXTRA_RMS_THRESHOLD, rmsThreshold.toDouble())
                            putExtra(VideoExportService.EXTRA_QUICK_TEST, false)
                        }
                        context.startForegroundService(intent)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD92323))
                ) {
                    Text("Gerar vídeo MP4", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(context, VideoExportService::class.java).apply {
                            action = VideoExportService.ACTION_START
                            putExtra(VideoExportService.EXTRA_ASPECT, selectedAspect.name)
                            putExtra(VideoExportService.EXTRA_RMS_THRESHOLD, rmsThreshold.toDouble())
                            putExtra(VideoExportService.EXTRA_QUICK_TEST, true)
                        }
                        context.startForegroundService(intent)
                    }
                ) {
                    Text("Modo Teste (10s)", fontSize = 12.sp)
                }
            }

            // Painel de Status da Exportação em Tempo Real
            when (val st = exportStatus) {
                is ExportStatus.Running -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = st.progressPercent / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFD92323)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Fase atual: ${st.phase} (${st.progressPercent}%)", color = Color.White, fontSize = 13.sp)
                    TextButton(
                        onClick = {
                            val cancelIntent = Intent(context, VideoExportService::class.java).apply {
                                action = VideoExportService.ACTION_CANCEL
                            }
                            context.startService(cancelIntent)
                        }
                    ) {
                        Text("Cancelar Exportação", color = Color(0xFFEF5350))
                    }
                }
                is ExportStatus.Completed -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("✅ Vídeo finalizado e salvo na Galeria (${st.displayPath})!", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, st.galleryUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Compartilhar Reportagem MP4"))
                        }) {
                            Text("Compartilhar MP4")
                        }
                        OutlinedButton(onClick = {
                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(st.galleryUri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(viewIntent)
                        }) {
                            Text("Abrir Vídeo")
                        }
                    }
                }
                is ExportStatus.Failed -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("❌ Erro em [${st.exactPhase}]: ${st.details}", color = Color(0xFFEF5350), fontSize = 13.sp)
                }
                ExportStatus.Idle -> {}
            }
        }

        // ETAPA 4 - Voz e Avatar
        StepCard(title = "ETAPA 4 • Voz Faber e Avatar Alinhado") {
            val offlineReady = remember { PiperTtsEngine(context).isOfflineFaberModelInstalled() }
            Text("• Voz Faber - Português Brasileiro (pt_BR-faber-medium.onnx)", color = Color.White, fontSize = 13.sp)
            Text("• Motor Piper integrado | Velocidade 0.95 (Fala pausada e eloquente)", color = Color(0xFFB8C2D4), fontSize = 13.sp)
            Text(
                text = if (offlineReady) "• Status da Síntese: 100% Offline no Dispositivo (ONNX NDK)"
                       else "• Status da Síntese: Arquitetura Híbrida Ativa (Adicione pt_BR-faber-medium.onnx em assets/piper para 100% ONNX)",
                color = Color(0xFF81C784),
                fontSize = 12.sp
            )
            Text("• Avatar de reportagem centralizado (Escala 60% anti-sobreposição)", color = Color(0xFFB8C2D4), fontSize = 13.sp)
            Text("• Três estados de boca alinhados | Boca fechada durante pausas", color = Color(0xFFB8C2D4), fontSize = 13.sp)

            Spacer(modifier = Modifier.height(8.dp))
            Text("Limiar RMS de Pausa Vocal: ${rmsThreshold.toInt()} (Padrão: 520 PCM 16-bit)", color = Color.White, fontSize = 12.sp)
            Slider(
                value = rmsThreshold,
                onValueChange = { rmsThreshold = it },
                valueRange = 200f..1200f
            )

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                AvatarPreviewItem(R.drawable.reporter_closed, "Closed (Pausa)")
                AvatarPreviewItem(R.drawable.reporter_open, "Open (Fala)")
                AvatarPreviewItem(R.drawable.reporter_o, "Formato O")
            }
        }
    }
}

@Composable
fun StepCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF151A24), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF263042), RoundedCornerShape(10.dp))
            .padding(14.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFFD92323),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
fun AvatarPreviewItem(resId: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = label,
            modifier = Modifier
                .size(80.dp)
                .border(1.dp, Color(0xFF37474F), RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color(0xFFB0BEC5), fontSize = 11.sp)
    }
}
