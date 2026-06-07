package com.momir.android.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.momir.android.model.Creature
import java.io.ByteArrayOutputStream
import kotlin.math.min
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set

object ThermalRenderer {
    private const val PADDING = 12f
    private const val MAX_LINES_PER_BLOCK = 255

    fun renderCardBitmap(creature: Creature, artBitmap: Bitmap?, printWidth: Int = 576): Bitmap {
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 36f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val typePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f
            color = Color.BLACK
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            color = Color.BLACK
        }
        val ptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        val rulePaint = Paint().apply { color = Color.BLACK; strokeWidth = 1f }

        val contentWidth = printWidth - (PADDING * 2)
        val nameHeight = 44f
        val typeHeight = 32f
        val textLineHeight = 28f
        val ptHeight = if (creature.power.isNotBlank() && creature.toughness.isNotBlank()) 42f else 0f

        val art = artBitmap?.let {
            val scaledHeight = (printWidth.toFloat() * (it.height.toFloat() / it.width.toFloat())).toInt()
            it.scale(printWidth, scaledHeight)
        }
        val rulesLines = wrapLines(creature.text, textPaint, contentWidth)
        val rulesHeight = if (rulesLines.isEmpty()) 0f else rulesLines.size * textLineHeight + 12f

        var totalHeight = 0f
        totalHeight += nameHeight + 7f
        if (art != null) totalHeight += art.height + 13f
        totalHeight += typeHeight + 7f
        if (rulesHeight > 0f) totalHeight += rulesHeight + 7f
        totalHeight += ptHeight + 10f

        val out = createBitmap(printWidth, totalHeight.toInt().coerceAtLeast(120))
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        var y = 36f
        canvas.drawText(creature.name, PADDING, y, namePaint)
        if (creature.manaCost.isNotBlank()) {
            val mw = namePaint.measureText(creature.manaCost)
            canvas.drawText(creature.manaCost, printWidth - PADDING - mw, y, namePaint)
        }
        y += 8f
        canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
        y += 8f

        if (art != null) {
            canvas.drawBitmap(art, 0f, y, null)
            y += art.height + 6f
            canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
            y += 8f
        }

        canvas.drawText(creature.type, PADDING, y + 24f, typePaint)
        y += 30f
        canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
        y += 8f

        if (rulesLines.isNotEmpty()) {
            for (line in rulesLines) {
                canvas.drawText(line, PADDING, y + 22f, textPaint)
                y += textLineHeight
            }
            y += 2f
            canvas.drawLine(PADDING, y, printWidth - PADDING, y, rulePaint)
            y += 8f
        }

        if (creature.power.isNotBlank() && creature.toughness.isNotBlank()) {
            val pt = "${creature.power} / ${creature.toughness}"
            val pw = ptPaint.measureText(pt)
            canvas.drawText(pt, printWidth - PADDING - pw, y + 32f, ptPaint)
        }

        return out
    }

    fun bitmapToMonochrome(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val out = createBitmap(w, h)

        // Match the web renderer: grayscale + Floyd-Steinberg dithering.
        // This keeps midtone detail and avoids the overly-dark threshold look.
        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = bitmap[x, y]
                gray[y * w + x] = (Color.red(c) * 0.299f) + (Color.green(c) * 0.587f) + (Color.blue(c) * 0.114f)
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val oldVal = gray[idx]
                val newVal = if (oldVal < 128f) 0f else 255f
                out[x, y] = if (newVal == 0f) Color.BLACK else Color.WHITE

                val err = oldVal - newVal
                if (x + 1 < w) gray[idx + 1] += err * (7f / 16f)
                if (y + 1 < h && x - 1 >= 0) gray[(y + 1) * w + (x - 1)] += err * (3f / 16f)
                if (y + 1 < h) gray[(y + 1) * w + x] += err * (5f / 16f)
                if (y + 1 < h && x + 1 < w) gray[(y + 1) * w + (x + 1)] += err * (1f / 16f)
            }
        }

        return out
    }

    fun buildPrintCommands(bitmap: Bitmap, init: ByteArray, finalize: ByteArray): ByteArray {
        val bw = bitmapToMonochrome(bitmap)
        val bytesPerLine = bw.width / 8
        val packed = packImage(bw, bytesPerLine)

        val out = ByteArrayOutputStream()
        out.write(init)

        val totalLines = bw.height
        var offset = 0
        while (offset < totalLines) {
            val lines = min(MAX_LINES_PER_BLOCK, totalLines - offset)
            out.write(byteArrayOf(0x1d, 0x76, 0x30, 0x00))
            out.write(le2(bytesPerLine))
            out.write(le2(lines))

            val start = offset * bytesPerLine
            out.write(packed, start, lines * bytesPerLine)
            offset += lines
        }

        out.write(finalize)
        return out.toByteArray()
    }

    private fun packImage(bitmap: Bitmap, bytesPerLine: Int): ByteArray {
        val out = ByteArray(bytesPerLine * bitmap.height)
        var i = 0
        for (y in 0 until bitmap.height) {
            for (xb in 0 until bytesPerLine) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xb * 8 + bit
                    val black = Color.red(bitmap[x, y]) < 128
                    if (black) b = b or (1 shl (7 - bit))
                }
                out[i++] = if (b == 0x0A) 0x14 else b.toByte()
            }
        }
        return out
    }

    private fun le2(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            val words = paragraph.split(' ')
            var current = ""
            for (word in words) {
                val test = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(test) <= maxWidth) {
                    current = test
                } else {
                    if (current.isNotEmpty()) lines.add(current)
                    current = word
                }
            }
            if (current.isNotEmpty()) lines.add(current)
        }
        return lines
    }
}
