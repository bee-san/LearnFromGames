// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * A diary entry as a picture to share, and the diary's widget: the game's
 * screen, with what was written on it at the top left, away from the game's
 * own text box, like a postcard. "learn from games" sits faint in the corner.
 */
object Postcard {
    /** What is written on the picture, top to bottom; an empty one is left out. */
    data class Words(
        /** The widget's "One month ago today". */
        val label: String = "",
        val line: String = "",
        val translation: String = "",
        val entry: String = "",
        val meta: String = "",
    )

    const val WIDTH = 1280
    const val HEIGHT = 720
    const val WATERMARK = "learn from games"

    /** [scale] is 1 for the shared picture's sizes at [WIDTH] by [HEIGHT]. */
    fun render(picture: Bitmap?, words: Words, width: Int = WIDTH, height: Int = HEIGHT, scale: Float = 1f, watermark: Boolean = true): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF0F0D0E.toInt())
        if (picture != null) {
            // Filled edge to edge, the middle kept.
            val k = maxOf(width / picture.width.toFloat(), height / picture.height.toFloat())
            val w = width / k
            val h = height / k
            val left = (picture.width - w) / 2
            val top = (picture.height - h) / 2
            canvas.drawBitmap(
                picture, Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()), Rect(0, 0, width, height),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
        }
        fun px(v: Float) = v * scale
        // A small widget has less room to give to its edges.
        val margin = minOf(px(64f), width * 0.09f)
        val column = (width - 2 * margin - if (watermark) px(210f) else 0f).toInt().coerceAtMost(px(800f).toInt()).coerceAtLeast(1)

        class Part(val text: String, size: Float, leading: Float, serif: Boolean, bold: Boolean, alpha: Int, val lines: Int) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 255, 255, 255)
                textSize = px(size)
                typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setShadowLayer(px(12f), 0f, px(1f), 0x99000000.toInt())
            }
            val spacing = px(leading) - paint.getFontMetrics(null)
            fun layout(maxLines: Int): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, column)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(spacing, 1f).setIncludePad(false)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
        }
        val parts = listOfNotNull(
            words.label.takeIf { it.isNotBlank() }?.let { Part(it, 20f, 28f, serif = false, bold = true, alpha = 225, lines = 1) },
            words.line.takeIf { it.isNotBlank() }?.let { Part("「${it.replace('\n', ' ')}」", 26f, 38f, serif = true, bold = false, alpha = 225, lines = 2) },
            words.translation.takeIf { it.isNotBlank() }?.let { Part("“${it.trim()}”", 22f, 30f, serif = false, bold = false, alpha = 225, lines = 2) },
            words.entry.takeIf { it.isNotBlank() }?.let { Part(it.trim(), 40f, 56f, serif = true, bold = true, alpha = 255, lines = 8) },
            words.meta.takeIf { it.isNotBlank() }?.let { Part(it, 20f, 28f, serif = false, bold = false, alpha = 225, lines = 1) },
        )
        // The entry gives way first when it would run off the picture.
        val gap = px(16f)
        val top = minOf(px(58f), height * 0.14f)
        val room = height - top - margin
        var entryLines = 8
        var layouts = parts.map { it.layout(it.lines) }
        while (entryLines > 1 && layouts.sumOf { it.height } + gap * (layouts.size - 1) > room) {
            entryLines--
            layouts = parts.map { it.layout(if (it.lines == 8) entryLines else it.lines) }
        }
        val textHeight = layouts.sumOf { it.height } + gap * (layouts.size - 1).coerceAtLeast(0)

        // Dark at the top, where the words are, fading out below them.
        val scrim = (top + textHeight + px(150f)).coerceIn(height * 0.4f, height.toFloat())
        canvas.drawRect(
            0f, 0f, width.toFloat(), scrim,
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, scrim, intArrayOf(0xC7000000.toInt(), 0x9E000000.toInt(), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
            },
        )
        val inset = minOf(px(20f), margin / 2.6f)
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset), px(4f), px(4f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = px(1.5f)
                color = Color.argb(178, 255, 255, 255)
            },
        )
        var y = top
        for (layout in layouts) {
            canvas.save()
            canvas.translate(margin, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap
        }
        if (watermark) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(102, 255, 255, 255)
                textSize = px(18f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                letterSpacing = 0.04f
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(WATERMARK, width - margin, px(68f) + paint.textSize, paint)
        }
        return out
    }

    /** A picture from a file, no larger than it needs to be to fill [width]; null when the file is gone. */
    fun decode(path: String?, width: Int = WIDTH): Bitmap? {
        if (path == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
