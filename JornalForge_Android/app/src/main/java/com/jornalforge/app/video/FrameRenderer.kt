package com.jornalforge.app.video

import android.content.Context
import android.graphics.*
import com.jornalforge.app.R
import com.jornalforge.app.audio.MouthState
import com.jornalforge.app.model.NormalizedScene
import com.jornalforge.app.model.VideoAspect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class FrameRenderer(private val context: Context) {

    private val bitmapClosed: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.reporter_closed)
    private val bitmapOpen: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.reporter_open)
    private val bitmapO: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.reporter_o)

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0A0C10") // Quase preto jornalístico
        style = Paint.Style.FILL
    }

    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D92323") // Sublinhado vermelho jornalístico
        style = Paint.Style.FILL
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val captionNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E4EC") // Cinza-claro
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val captionKeywordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2E2E") // Vermelho para palavras-chave na mesma camada
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /**
     * Renderiza um único frame em memória e salva imediatamente em disco, reciclando o Bitmap para nunca estourar a RAM.
     */
    fun renderAndSaveFrame(
        videoTitle: String,
        scene: NormalizedScene,
        localTime: Double,
        sceneDuration: Double,
        mouthState: MouthState,
        aspect: VideoAspect,
        outputFile: File
    ) {
        val width = aspect.width
        val height = aspect.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Fundo preto / quase preto
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val safeMarginX = width * 0.02f
        val safeMarginY = height * 0.02f
        val safeWidth = width * 0.96f

        // 2. Título no topo (Branco, Negrito, centralizado, até 2 linhas sem truncar, sublinhado vermelho)
        val titleBottomY = drawTopTitle(canvas, videoTitle, width, height, safeMarginY, safeWidth)

        // 3. Layout específico para Horizontal (16:9) vs Vertical (9:16)
        if (aspect == VideoAspect.HORIZONTAL_16_9) {
            // Horizontal: Texto ocupa 70% à esquerda, Avatar ocupa 30% à direita reduzido a ~60%
            val textAreaLeft = safeMarginX
            val textAreaWidth = (width - safeMarginX * 2f) * 0.68f
            val avatarZoneLeft = textAreaLeft + textAreaWidth + (width * 0.02f)
            val avatarZoneRight = width - safeMarginX

            // Desenhar Headline à esquerda
            val headlineY = drawHeadline(
                canvas = canvas,
                text = scene.onScreenText,
                textRate = scene.textRate,
                areaLeft = textAreaLeft,
                areaWidth = textAreaWidth,
                topY = titleBottomY + 48f,
                centerHorizontal = false
            )

            // Desenhar Legenda progressiva (+10% adiantada, até 6 linhas visíveis, janela deslizante)
            drawProgressiveCaption(
                canvas = canvas,
                narration = scene.narration,
                keywords = scene.keywords,
                localTime = localTime,
                audioDuration = sceneDuration,
                areaLeft = textAreaLeft,
                areaWidth = textAreaWidth,
                topY = headlineY + 45f,
                maxVisibleLines = 6,
                fontSize = 42f
            )

            // Desenhar Avatar reduzido (~60% do tamanho original) centralizado na coluna direita
            drawAvatar(
                canvas = canvas,
                mouthState = mouthState,
                zoneLeft = avatarZoneLeft,
                zoneTop = titleBottomY + 30f,
                zoneRight = avatarZoneRight,
                zoneBottom = height - safeMarginY,
                scaleFactor = 0.60f
            )
        } else {
            // Vertical 9:16: Headline no topo, Avatar centralizado no meio (~60%), Legenda embaixo (até 8 linhas)
            val textAreaLeft = safeMarginX
            val textAreaWidth = safeWidth

            val headlineY = drawHeadline(
                canvas = canvas,
                text = scene.onScreenText,
                textRate = scene.textRate,
                areaLeft = textAreaLeft,
                areaWidth = textAreaWidth,
                topY = titleBottomY + 36f,
                centerHorizontal = true
            )

            val avatarZoneTop = headlineY + 24f
            val avatarZoneBottom = height * 0.56f

            drawAvatar(
                canvas = canvas,
                mouthState = mouthState,
                zoneLeft = safeMarginX,
                zoneTop = avatarZoneTop,
                zoneRight = width - safeMarginX,
                zoneBottom = avatarZoneBottom,
                scaleFactor = 0.58f
            )

            drawProgressiveCaption(
                canvas = canvas,
                narration = scene.narration,
                keywords = scene.keywords,
                localTime = localTime,
                audioDuration = sceneDuration,
                areaLeft = textAreaLeft,
                areaWidth = textAreaWidth,
                topY = avatarZoneBottom + 36f,
                maxVisibleLines = 8,
                fontSize = 40f
            )
        }

        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        bitmap.recycle()
    }

    private fun drawTopTitle(
        canvas: Canvas,
        title: String,
        screenWidth: Int,
        screenHeight: Int,
        safeMarginY: Float,
        safeWidth: Float
    ): Float {
        var fontSize = if (screenWidth > screenHeight) 46f else 42f
        titlePaint.textSize = fontSize
        var lines = wrapWordsIntoLines(title.uppercase(), titlePaint, safeWidth)

        // Ajusta a fonte dinamicamente para caber em até 2 linhas sem jamais cortar caracteres
        while (lines.size > 2 && fontSize > 22f) {
            fontSize -= 2f
            titlePaint.textSize = fontSize
            lines = wrapWordsIntoLines(title.uppercase(), titlePaint, safeWidth)
        }

        val centerX = screenWidth / 2f
        var currentY = safeMarginY + fontSize + 10f
        var maxLineW = 0f

        for (line in lines) {
            canvas.drawText(line, centerX, currentY, titlePaint)
            val w = titlePaint.measureText(line)
            if (w > maxLineW) maxLineW = w
            currentY += fontSize * 1.22f
        }

        // Sublinhado vermelho abaixo do bloco completo do título
        val underlineTop = currentY - (fontSize * 0.75f)
        val halfW = (maxLineW / 2f).coerceAtLeast(180f)
        canvas.drawRect(centerX - halfW, underlineTop, centerX + halfW, underlineTop + 6f, underlinePaint)

        return underlineTop + 20f
    }

    private fun drawHeadline(
        canvas: Canvas,
        text: String,
        textRate: String,
        areaLeft: Float,
        areaWidth: Float,
        topY: Float,
        centerHorizontal: Boolean
    ): Float {
        val baseSize = if (textRate.equals("high", ignoreCase = true)) 44f else 34f
        headlinePaint.textSize = baseSize
        headlinePaint.textAlign = if (centerHorizontal) Paint.Align.CENTER else Paint.Align.LEFT

        val lines = wrapWordsIntoLines(text.uppercase(), headlinePaint, areaWidth)
        var currentY = topY + baseSize
        val drawX = if (centerHorizontal) areaLeft + (areaWidth / 2f) else areaLeft

        for (line in lines) {
            canvas.drawText(line, drawX, currentY, headlinePaint)
            currentY += baseSize * 1.2f
        }
        return currentY
    }

    /**
     * Renderiza a legenda progressiva palavra por palavra com adiantamento intencional de 10%:
     * captionProgress = min(1.0, localTime / audioDuration + 0.10)
     * Mede a largura real de cada palavra e pinta as palavras-chave em vermelho na mesma camada.
     */
    private fun drawProgressiveCaption(
        canvas: Canvas,
        narration: String,
        keywords: List<String>,
        localTime: Double,
        audioDuration: Double,
        areaLeft: Float,
        areaWidth: Float,
        topY: Float,
        maxVisibleLines: Int,
        fontSize: Float
    ) {
        val allWords = narration.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (allWords.isEmpty()) return

        val safeDuration = audioDuration.coerceAtLeast(0.1)
        val captionProgress = min(1.0, (localTime / safeDuration) + 0.10)
        val revealedCount = (allWords.size * captionProgress).toInt().coerceIn(1, allWords.size)
        val visibleWords = allWords.subList(0, revealedCount)

        captionNormalPaint.textSize = fontSize
        captionKeywordPaint.textSize = fontSize
        val spaceWidth = captionNormalPaint.measureText(" ")

        val normalizedKeywords = keywords.map { cleanPunctuation(it).lowercase() }.filter { it.isNotEmpty() }

        // Agrupar palavras reveladas em linhas medidas pela fonte real
        val lines = mutableListOf<MutableList<String>>()
        var currentLine = mutableListOf<String>()
        var currentLineWidth = 0f

        for (word in visibleWords) {
            val wordWidth = captionNormalPaint.measureText(word)
            val addedWidth = if (currentLine.isEmpty()) wordWidth else spaceWidth + wordWidth
            if (currentLine.isNotEmpty() && currentLineWidth + addedWidth > areaWidth) {
                lines.add(currentLine)
                currentLine = mutableListOf(word)
                currentLineWidth = wordWidth
            } else {
                currentLine.add(word)
                currentLineWidth += addedWidth
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        // Janela deslizante: mostra sempre as últimas N linhas (6 no horizontal, 8 no vertical)
        val slidingWindowLines = if (lines.size > maxVisibleLines) {
            lines.subList(lines.size - maxVisibleLines, lines.size)
        } else {
            lines
        }

        var currentY = topY + fontSize
        for (lineWords in slidingWindowLines) {
            val lineString = lineWords.joinToString(" ")
            val totalLineWidth = captionNormalPaint.measureText(lineString)
            // Centralizar cada linha dentro da área textual
            var wordX = areaLeft + ((areaWidth - totalLineWidth) / 2f).coerceAtLeast(0f)

            for (word in lineWords) {
                val cleanedWord = cleanPunctuation(word).lowercase()
                val isKeyword = normalizedKeywords.any { kw ->
                    cleanedWord == kw || (kw.length > 3 && cleanedWord.contains(kw))
                }
                val paintToUse = if (isKeyword) captionKeywordPaint else captionNormalPaint
                canvas.drawText(word, wordX, currentY, paintToUse)
                wordX += paintToUse.measureText(word) + spaceWidth
            }
            currentY += fontSize * 1.30f
        }
    }

    private fun drawAvatar(
        canvas: Canvas,
        mouthState: MouthState,
        zoneLeft: Float,
        zoneTop: Float,
        zoneRight: Float,
        zoneBottom: Float,
        scaleFactor: Float
    ) {
        val sourceBitmap = when (mouthState) {
            MouthState.CLOSED -> bitmapClosed
            MouthState.OPEN -> bitmapOpen
            MouthState.O_SHAPE -> bitmapO
        }

        val zoneWidth = (zoneRight - zoneLeft).coerceAtLeast(100f)
        val zoneHeight = (zoneBottom - zoneTop).coerceAtLeast(100f)

        val aspect = sourceBitmap.width.toFloat() / sourceBitmap.height.toFloat()
        var targetHeight = zoneHeight * scaleFactor
        var targetWidth = targetHeight * aspect

        if (targetWidth > zoneWidth * 0.92f) {
            targetWidth = zoneWidth * 0.92f
            targetHeight = targetWidth / aspect
        }

        val left = zoneLeft + (zoneWidth - targetWidth) / 2f
        val top = zoneTop + (zoneHeight - targetHeight) / 2f
        val destRect = RectF(left, top, left + targetWidth, top + targetHeight)

        canvas.drawBitmap(sourceBitmap, null, destRect, null)
    }

    private fun wrapWordsIntoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines
    }

    private fun cleanPunctuation(input: String): String {
        return input.replace(Regex("[^\\p{L}\\p{N}]"), "")
    }
}
