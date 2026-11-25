// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.Knowing
import io.github.beesan.lfg.core.LineMarks
import io.github.beesan.lfg.core.TextLine

/** One thing drawn over a game's text: a reading in its [box], or with no [text] a stroke filling it. */
internal class DrawnMark(val box: Box, val text: String?, val color: Int, val vertical: Boolean = false)

/**
 * Furigana over the kanji of a game's text, and a stroke under each word for
 * its Anki card, drawn in one window the size of the screen that no touch
 * lands on. It draws what [place] worked out, in the screen's pixels.
 */
@SuppressLint("ViewConstructor")
internal class TextMarksView(context: Context) : View(context) {
    private var drawn: List<DrawnMark> = emptyList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)

    fun show(marks: List<DrawnMark>) {
        drawn = marks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (mark in drawn) {
            val b = mark.box
            val text = mark.text
            if (text == null) {
                stroke.color = mark.color
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), stroke)
                continue
            }
            fill.color = mark.color
            if (mark.vertical) {
                // Down the side of the column, a character under a character.
                val size = minOf(b.width * 0.9f, b.height.toFloat() / text.length)
                setSize(size)
                val cx = (b.left + b.right) / 2f
                var y = b.top + (b.height - size * text.length) / 2f - fill.ascent() * 0.9f
                for (c in text) {
                    canvas.drawText(c.toString(), cx, y, outline)
                    canvas.drawText(c.toString(), cx, y, fill)
                    y += size
                }
            } else {
                setSize(b.height * 0.86f)
                val y = b.bottom - fill.descent() * 0.6f
                val cx = (b.left + b.right) / 2f
                canvas.drawText(text, cx, y, outline)
                canvas.drawText(text, cx, y, fill)
            }
        }
    }

    private fun setSize(size: Float) {
        fill.textSize = size
        outline.textSize = size
        outline.strokeWidth = size * 0.28f
    }

    companion object {
        private val measure = Paint()

        /** A card still being learnt: amber, lighter than the grey of a mature one and darker than the accent of none. */
        private const val LEARNING = 0xFFE0B46A.toInt()

        /** Where [marks] are drawn for the line whose place on screen is [line]. */
        fun place(context: Context, line: TextLine, marks: LineMarks): List<DrawnMark> {
            val out = ArrayList<DrawnMark>()
            for (ruby in marks.rubies) {
                // A word that runs on to the next row has its reading over where it starts.
                val over = line.spanBoxes(ruby.start, ruby.end).firstOrNull() ?: continue
                if (line.vertical) {
                    val wide = (over.width * 0.45f).toInt().coerceAtLeast(context.dp(8f))
                    val tall = maxOf(over.height, wide * ruby.reading.length)
                    val top = (over.top + over.bottom - tall) / 2
                    out += DrawnMark(Box(over.right, top, over.right + wide, top + tall), ruby.reading, Color.WHITE, vertical = true)
                } else {
                    val tall = (over.height * 0.45f).toInt().coerceAtLeast(context.dp(8f))
                    measure.textSize = tall * 0.86f
                    val wide = maxOf(over.width, measure.measureText(ruby.reading).toInt() + tall / 2)
                    val left = (over.left + over.right - wide) / 2
                    out += DrawnMark(Box(left, over.top - tall, left + wide, over.top), ruby.reading, Color.WHITE)
                }
            }
            val thick = context.dp(2f).coerceAtLeast(2)
            for (word in marks.words) {
                val color = when (word.knowing) {
                    Knowing.MATURE -> Ink.DIM
                    Knowing.LEARNING -> LEARNING
                    Knowing.NEW -> Ink.ACCENT
                }
                for (piece in line.spanBoxes(word.start, word.end)) {
                    out += if (line.vertical) {
                        DrawnMark(Box(piece.left - thick - 1, piece.top + 2, piece.left - 1, piece.bottom - 2), null, color)
                    } else {
                        DrawnMark(Box(piece.left + 2, piece.bottom + 1, piece.right - 2, piece.bottom + 1 + thick), null, color)
                    }
                }
            }
            return out
        }
    }
}
