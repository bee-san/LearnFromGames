// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.core.WheelAim
import io.github.beesan.lfg.core.WheelLevels
import io.github.beesan.lfg.core.WheelSlice
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The wheel over a game: the screen dimmed but for the line it acts on
 * ([holes]), and a ring of [WheelAction]s round a middle that names the one
 * pointed at ([hover]). A stick or the D-pad points through [OverlayHost]; a
 * finger points here, and lifting it on a slice picks that slice, anywhere
 * else puts the wheel away.
 */
@SuppressLint("ViewConstructor")
internal class WheelView(
    context: Context,
    private val onPick: (WheelSlice) -> Unit,
    private val onDismiss: () -> Unit,
) : View(context) {
    /** The slice pointed at, by its place in [slices]; null for none yet. */
    var hover: Int? = null
        set(value) {
            if (field == value) return
            field = value
            contentDescription = "LFG wheel" + (value?.let { ": " + actions[it].label } ?: "")
            invalidate()
        }

    /** The rows of the line the wheel acts on, left undimmed, in this view's pixels. */
    var holes: List<Box> = emptyList()
        set(value) {
            field = value
            layOut()
            invalidate()
        }

    /**
     * The game's box 1 and box 2, in this view's pixels, each null while it is
     * not drawn. Each is outlined; pointed at, it is left undimmed in place of
     * the line.
     */
    var boxes: List<Box?> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The level of the wheel showing, clockwise from the top. A new one points at nothing. */
    var slices: List<WheelSlice> = WheelLevels.TOP
        set(value) {
            field = value
            hover = null
            icons = value.map { PathParser.createPathFromPathData(ICONS.getValue(it.action)) }
            layOut()
            invalidate()
        }
    private val actions: List<WheelSlice> get() = slices
    private var icons = slices.map { PathParser.createPathFromPathData(ICONS.getValue(it.action)) }

    private val dim = Paint().apply { color = Color.argb(158, 0, 0, 0) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f).toFloat()
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f).toFloat()
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = text(13.5f, bold = true).apply { textAlign = Paint.Align.CENTER }
    private val title = text(18f, bold = true)
    private val about = text(12f)
    private val number = text(11f, bold = true).apply { textAlign = Paint.Align.CENTER }
    private val tagText = text(12f, bold = true).apply { textAlign = Paint.Align.CENTER }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(context.dp(6f).toFloat(), context.dp(5f).toFloat()), 0f)
    }

    private var cx = 0f
    private var cy = 0f
    private var outer = 0f
    private var inner = 0f
    private val shapes = ArrayList<Path>()
    private val rims = ArrayList<Path>()
    private val iconMatrix = Matrix()
    private val iconPath = Path()
    private val oval = RectF()

    /** A touch that began in the corner is our own tap bringing the controller here ([OverlayHost]), not the player's. */
    private var ownTap = false

    init {
        contentDescription = "LFG wheel"
    }

    private fun text(sizeSp: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layOut()
    }

    /** Where the ring goes: the middle of the screen, or of the room above or below the line when it would cover it. */
    private fun layOut() {
        if (width <= 0 || height <= 0) return
        val top = 0f
        outer = minOf(context.dp(155f).toFloat(), height * 0.44f, width * 0.34f)
        inner = outer * 0.44f
        cx = width / 2f
        cy = height / 2f
        val line = holes.reduceOrNull { a, b -> a.union(b) }
        if (line != null) {
            val room = 2 * outer + context.dp(24f)
            val ring = Box((cx - outer).toInt(), (cy - outer).toInt(), (cx + outer).toInt(), (cy + outer).toInt())
            if (ring.intersects(line)) {
                val above = line.top - top
                val below = height - line.bottom
                cy = when {
                    above >= room && above >= below -> top + above / 2f
                    below >= room -> line.bottom + below / 2f
                    else -> cy
                }
            }
        }
        // A gap of the same few pixels between slices all the way along.
        val gap = context.dp(3f) / 2f
        val outerGap = Math.toDegrees((gap / outer).toDouble()).toFloat()
        val innerGap = Math.toDegrees((gap / inner).toDouble()).toFloat()
        val rimRadius = outer + context.dp(7f)
        val rimGap = Math.toDegrees((context.dp(6f) / rimRadius).toDouble()).toFloat()
        val step = 360f / actions.size
        while (shapes.size < actions.size) shapes += Path()
        while (rims.size < actions.size) rims += Path()
        actions.indices.forEach { i ->
            val mid = -90f + i * step
            shapes[i].apply {
                rewind()
                oval.set(cx - outer, cy - outer, cx + outer, cy + outer)
                arcTo(oval, mid - step / 2 + outerGap, step - 2 * outerGap, true)
                oval.set(cx - inner, cy - inner, cx + inner, cy + inner)
                arcTo(oval, mid + step / 2 - innerGap, -(step - 2 * innerGap))
                close()
            }
            rims[i].apply {
                rewind()
                oval.set(cx - rimRadius, cy - rimRadius, cx + rimRadius, cy + rimRadius)
                arcTo(oval, mid - step / 2 + rimGap, step - 2 * rimGap, true)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        // The game dimmed round the line, which is outlined; or, with a box pointed at, round that box.
        val pad = context.dp(5f).toFloat()
        val corner = context.dp(6f).toFloat()
        val pointed = hover?.let { actions[it] }?.takeIf { it.action == WheelAction.BOX }?.box
        val lit = pointed?.let { boxes.getOrNull(it - 1) }?.let { listOf(it) } ?: holes
        canvas.save()
        for (b in lit) canvas.clipOutRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.restore()
        hole.color = Ink.ACCENT
        for (b in lit) canvas.drawRoundRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, corner, corner, hole)
        boxes.forEachIndexed { i, b -> if (b != null) drawBoxTag(canvas, b, i + 1, on = pointed == i + 1, pad, corner) }

        val step = 360f / actions.size
        val middle = (outer + inner) / 2
        val iconSize = context.dp(22f).toFloat()
        actions.indices.forEach { i ->
            val on = i == hover
            fill.color = if (on) Ink.FILL else Ink.SURFACE
            edge.color = if (on) Ink.FILL else Ink.BORDER_STRONG
            canvas.drawPath(shapes[i], fill)
            canvas.drawPath(shapes[i], edge)
            if (on) {
                rim.color = Ink.ACCENT
                canvas.drawPath(rims[i], rim)
            }
            val ink = if (on) Ink.ON_FILL else Ink.TEXT
            val angle = Math.toRadians((-90f + i * step).toDouble())
            val x = cx + middle * cos(angle).toFloat()
            val y = cy + middle * sin(angle).toFloat()
            // The icons are drawn on a 24-unit square, as the design's are.
            val scale = iconSize / 24f
            iconMatrix.setScale(scale, scale)
            iconMatrix.postTranslate(x - iconSize / 2, y - iconSize / 2 - context.dp(9f))
            icons[i].transform(iconMatrix, iconPath)
            icon.color = ink
            icon.strokeWidth = 1.9f * scale
            canvas.drawPath(iconPath, icon)
            // A box's icon is a frame with its number in it.
            actions[i].takeIf { it.action == WheelAction.BOX }?.box?.let { slot ->
                number.color = ink
                canvas.drawText(slot.toString(), x, y - context.dp(9f) - (number.ascent() + number.descent()) / 2, number)
            }
            label.color = ink
            canvas.drawText(actions[i].label, x, y + context.dp(9f) + label.textSize * 0.8f, label)
        }
        drawMiddle(canvas)
    }

    /** A box's number at its top left corner, and a dashed outline round a box not pointed at. */
    private fun drawBoxTag(canvas: Canvas, b: Box, slot: Int, on: Boolean, pad: Float, corner: Float) {
        if (!on) {
            dashed.color = Ink.DIM
            canvas.drawRoundRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, corner, corner, dashed)
        }
        val size = context.dp(22f).toFloat()
        val left = b.left - pad
        // Above the box, or inside it when it is at the top of the screen.
        val top = if (b.top - pad - size - context.dp(3f) >= 0) b.top - pad - size - context.dp(3f) else b.top - pad + context.dp(3f)
        fill.color = if (on) Ink.FILL else Ink.SURFACE
        edge.color = if (on) Ink.FILL else Ink.BORDER_STRONG
        val r = context.dp(4f).toFloat()
        canvas.drawRoundRect(left, top, left + size, top + size, r, r, fill)
        canvas.drawRoundRect(left, top, left + size, top + size, r, r, edge)
        tagText.color = if (on) Ink.ON_FILL else Ink.DIM
        canvas.drawText(slot.toString(), left + size / 2, top + size / 2 - (tagText.ascent() + tagText.descent()) / 2, tagText)
    }

    /** The middle: which way is pointed, the slice's name and what it does. */
    private fun drawMiddle(canvas: Canvas) {
        val radius = inner - context.dp(6f)
        fill.color = Ink.BG
        edge.color = Ink.BORDER_STRONG
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, edge)

        val chosen = hover?.let { actions[it] }
        val undrawn = chosen?.action == WheelAction.BOX && boxes.getOrNull(chosen.box - 1) == null
        val textWidth = (radius * 1.55f).toInt().coerceAtLeast(1)
        title.color = if (chosen == null) Ink.DIM else Ink.TEXT
        about.color = Ink.DIM
        val name = StaticLayout.Builder.obtain(chosen?.label ?: "Point", 0, (chosen?.label ?: "Point").length, title, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        val line = if (undrawn) "Not drawn yet" else chosen?.about ?: "with a stick"
        val more = StaticLayout.Builder.obtain(line, 0, line.length, about, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()

        // A ring with a dot towards the slice pointed at, as the stick is.
        val dial = context.dp(9f).toFloat()
        val gap = context.dp(6f).toFloat()
        val total = 2 * dial + gap + name.height + context.dp(3f) + more.height
        var y = cy - total / 2
        edge.color = Ink.BORDER_STRONG
        canvas.drawCircle(cx, y + dial, dial, edge)
        fill.color = Ink.ACCENT
        val angle = hover?.let { Math.toRadians((-90.0 + it * 360.0 / actions.size)) }
        val reach = dial * 0.55f
        canvas.drawCircle(
            cx + (angle?.let { reach * cos(it).toFloat() } ?: 0f), y + dial + (angle?.let { reach * sin(it).toFloat() } ?: 0f),
            context.dp(3.5f).toFloat(), fill,
        )
        y += 2 * dial + gap
        canvas.save()
        canvas.translate(cx - textWidth / 2f, y)
        name.draw(canvas)
        canvas.translate(0f, name.height + context.dp(3f).toFloat())
        more.draw(canvas)
        canvas.restore()
    }

    /** The slice under a finger at ([x], [y]), or null for the middle and outside the ring. */
    private fun sliceAt(x: Float, y: Float): Int? {
        val d = hypot(x - cx, y - cy)
        if (d < inner * 0.85f || d > outer + context.dp(36f)) return null
        return WheelAim.slice(x - cx, y - cy, actions.size, dead = 0f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ownTap = event.x < CORNER_PX && event.y < CORNER_PX
        if (ownTap) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> sliceAt(event.x, event.y)?.let { hover = it }
            MotionEvent.ACTION_UP -> {
                val slice = sliceAt(event.x, event.y)
                if (slice != null) onPick(actions[slice]) else onDismiss()
            }
        }
        return true
    }

    companion object {
        /** Where [OverlayHost]'s own tap lands, which the wheel leaves alone. */
        private const val CORNER_PX = 16

        /** A box's corners, with room for its number in the middle. */
        private const val BOX_ICON = "M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16"

        /** The slices' icons, as the design draws them: strokes on a 24-unit square. */
        private val ICONS = mapOf(
            WheelAction.TRANSLATE to "M4 5h8M8 3v2M5.5 5c.7 2.9 2.7 5 5.5 6M10.5 5c-.7 2.9-2.7 5-5.5 6M13 21l4-10 4 10M14.4 18h5.2",
            WheelAction.ASK to "M20.5 12a8.5 8.5 0 0 1-12.3 7.6L3.5 20.5l1-4.4A8.5 8.5 0 1 1 20.5 12zM9.7 9.6a2.4 2.4 0 0 1 4.6.8c0 1.6-2.3 2-2.3 3.3M12 16.4h.01",
            WheelAction.EXPLAIN to "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.7.5 1.1 1.3 1.1 2.1V16h5v-.1c0-.8.4-1.6 1.1-2.1A6 6 0 0 0 12 3z",
            WheelAction.RECAP to "M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3 4v5h5M12 7.5V12l3 2",
            WheelAction.NOTE to "M4 20h4L19 9l-4-4L4 16v4zM13.5 6.5l4 4",
            WheelAction.BOX to BOX_ICON,
            // Two boxes, one over the other.
            WheelAction.BOXES to "M3.5 4.5h11v8h-11zM9.5 11.5h11v8h-11z",
            // A box with a line across it: read once.
            WheelAction.READ_ONCE to "$BOX_ICON M8 12h8",
            // A box with a plus in it.
            WheelAction.NEW_BOX to "$BOX_ICON M12 8.5v7M8.5 12h7",
            // A sparkle.
            WheelAction.AI to "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9zM18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z",
            WheelAction.LINES to "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
        )
    }
}
