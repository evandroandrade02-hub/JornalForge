package com.jornalforge.app.model

import kotlinx.serialization.json.*

data class NormalizedScript(
    val videoTitle: String,
    val subtitle: String = "",
    val editorialDisclaimer: String = "",
    val scenes: List<NormalizedScene>
)

data class NormalizedScene(
    val sceneNumber: Int,
    val editorialRole: String = "",
    val narration: String,
    val onScreenText: String,
    val textRate: String = "low",
    val lowerThird: String = "",
    val keywords: List<String> = emptyList()
)

enum class VideoAspect(val width: Int, val height: Int, val label: String) {
    HORIZONTAL_16_9(1920, 1080, "Horizontal 16:9 - Jornal / YouTube"),
    VERTICAL_9_16(1080, 1920, "Vertical 9:16 - Reportagem curta")
}

object ScriptParser {
    private val titleKeys = listOf("video_title", "title", "titulo", "titulo_principal", "headline")
    private val sceneKeys = listOf("scenes", "cenas", "scene_list", "roteiro")

    fun validateAndNormalize(rawJson: String): Result<NormalizedScript> {
        return try {
            val cleaned = rawJson.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val element = Json.parseToJsonElement(cleaned)
            if (element !is JsonObject) {
                return Result.failure(IllegalArgumentException("O JSON raiz deve ser um objeto { ... }."))
            }

            // Buscar chave de título equivalente
            val videoTitle = titleKeys.firstNotNullOfOrNull { key ->
                element[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            } ?: return Result.failure(
                IllegalArgumentException("Título ausente. Use 'video_title', 'title', 'titulo', 'titulo_principal' ou 'headline'.")
            )

            // Buscar lista de cenas equivalente e normalizar internamente para scenes
            val scenesArray = sceneKeys.firstNotNullOfOrNull { key ->
                element[key]?.jsonArray
            } ?: return Result.failure(
                IllegalArgumentException("Lista de cenas ausente. Use 'scenes', 'cenas', 'scene_list' ou 'roteiro'.")
            )

            val parsedScenes = mutableListOf<NormalizedScene>()
            for ((index, item) in scenesArray.withIndex()) {
                if (item !is JsonObject) continue
                val narration = item["narration"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val onScreen = item["on_screen_text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""

                if (narration.isNotEmpty() && onScreen.isNotEmpty()) {
                    val keywords = item["keywords"]?.jsonArray?.mapNotNull {
                        it.jsonPrimitive.contentOrNull?.trim()
                    } ?: emptyList()

                    // Nota: 'imagem' é ignorado visualmente (apenas aceito no JSON para retrocompatibilidade)
                    // Nota: 'emphasis' nunca é lido nem renderizado como camada separada para evitar duplicação
                    parsedScenes.add(
                        NormalizedScene(
                            sceneNumber = item["scene_number"]?.jsonPrimitive?.intOrNull ?: (index + 1),
                            editorialRole = item["editorial_role"]?.jsonPrimitive?.contentOrNull ?: "",
                            narration = narration,
                            onScreenText = onScreen,
                            textRate = item["text_rate"]?.jsonPrimitive?.contentOrNull ?: "low",
                            lowerThird = item["lower_third"]?.jsonPrimitive?.contentOrNull ?: "",
                            keywords = keywords
                        )
                    )
                }
            }

            if (parsedScenes.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("Nenhuma cena válida encontrada. Cada cena exige 'narration' e 'on_screen_text'.")
                )
            }

            Result.success(
                NormalizedScript(
                    videoTitle = videoTitle,
                    subtitle = element["subtitle"]?.jsonPrimitive?.contentOrNull ?: "",
                    editorialDisclaimer = element["editorial_disclaimer"]?.jsonPrimitive?.contentOrNull ?: "",
                    scenes = parsedScenes
                )
            )
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Erro de sintaxe JSON: ${e.localizedMessage}"))
        }
    }

    fun buildJournalisticPrompt(theme: String, durationMinutes: Int, sourceBase: String): String {
        val targetScenesMin = durationMinutes * 6
        val targetScenesMax = durationMinutes * 10
        return """
Atue como roteirista, repórter investigativo e editor de vídeos jornalísticos em português brasileiro. Produza um roteiro original, claro, responsável e baseado exclusivamente na fonte fornecida. Diferencie fatos, hipóteses e opiniões. Não invente dados, nomes, documentos ou citações. Estruture o vídeo como: gancho de curiosidade → promessa de bastidor → credibilidade contexto → tese → antecipação da objeção → desmonte com exemplos ou dados da fonte → explicação didática do mecanismo → revelação do verdadeiro interesse, somente quando sustentada pela fonte → posicionamento emocional responsável → ironia ou assinatura final. Use linguagem jornalística, investigativa, pausada e eloquente. Divida o conteúdo em cenas. Cada cena deve ter uma narração completa e natural para síntese de voz, texto curto na tela, taxa textual alta ou baixa e de 1 a 3 palavras-chave para destaque vermelho na legenda. Retorne somente JSON válido, sem Markdown e sem comentários fora do JSON.

PARÂMETROS DA REPORTAGEM:
- Tema principal: $theme
- Duração estimada: $durationMinutes minutos (crie aproximadamente de $targetScenesMin a $targetScenesMax cenas, cerca de 6 a 10 cenas por minuto conforme a complexidade).

ESTRUTURA JSON OBRIGATÓRIA:
{
  "video_title": "TÍTULO PRINCIPAL",
  "subtitle": "linha fina jornalística",
  "editorial_disclaimer": "frase curta sobre contexto e responsabilidade",
  "scenes": [
    {
      "scene_number": 1,
      "editorial_role": "gancho_de_curiosidade",
      "narration": "Texto exato da locução, sem marcações técnicas.",
      "on_screen_text": "HEADLINE CURTA",
      "text_rate": "high",
      "lower_third": "frase de contexto opcional",
      "keywords": ["PALAVRA-CHAVE"]
    }
  ]
}

FONTE-BASE FORNECIDA:
$sourceBase
        """.trimIndent()
    }

    fun getQuickTestScript(): NormalizedScript {
        return NormalizedScript(
            videoTitle = "TESTE DE CALIBRAGEM: VOZ FABER, AVATAR E LEGENDA",
            subtitle = "Verificação de pausa vocal e margem segura",
            editorialDisclaimer = "Cena de 10 segundos para homologação técnica",
            scenes = listOf(
                NormalizedScene(
                    sceneNumber = 1,
                    editorialRole = "teste_tecnico",
                    narration = "Este é o teste oficial do JornalForge no Android. ... Observe como a boca fecha completamente durante as pausas reais da locução, e como as palavras em destaque surgem na legenda sem invadir o avatar.",
                    onScreenText = "HOMOLOGAÇÃO DE SINCRONIA E MARGENS",
                    textRate = "high",
                    lowerThird = "Modo de Teste Curto (10s)",
                    keywords = listOf("JornalForge", "pausas", "destaque")
                )
            )
        )
    }
}
