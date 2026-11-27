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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import io.github.beesan.lfg.core.Box
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.core.WheelAim
import io.github.beesan.lfg.core.WheelSize
import io.github.beesan.lfg.core.WheelSlice
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The wheel over a game: the screen dimmed but for the line it acts on
 * ([holes]), and a ring of [WheelAction]s round a middle that names the one
 * pointed at ([hover]). A stick or the D-pad points through [OverlayHost]; a
 * finger points here, and lifting it on a slice picks that slice, in the
 * middle goes back a level ([onBack]), anywhere else puts the wheel away.
 */
@SuppressLint("ViewConstructor")
internal class WheelView(
    context: Context,
    private val onPick: (WheelSlice) -> Unit,
    private val onDismiss: () -> Unit,
    private val onBack: () -> Unit,
    /** A finger moved to another slice: the tick that is heard, which a stick's pointing asks for by itself. */
    private val onPoint: () -> Unit = {},
) : View(context) {
    /** The slice pointed at, by its place in [slices]; null for none yet. */
    var hover: Int? = null
        set(value) {
            if (field == value) return
            field = value
            contentDescription = "LFG wheel" + (value?.let { ": " + actions[it].label } ?: "")
            // A tick for each slice pointed at, to aim without looking away from the game.
            if (value != null && tick) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }

    /** The rows of the line the wheel acts on, left undimmed, in this view's pixels. */
    var holes: List<Box> = emptyList()
        set(value) {
            field = value
            layOut()
            invalidate()
        }

    /** Which of [boxes] (box 1 is 1) are read all the time beside the dialogue box: outlined whole, not dashed. */
    var always: Set<Int> = emptySet()
        set(value) {
            if (field == value) return
            field = value
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

    /** The wheel showing, clockwise from the top. A new one points at nothing. */
    var slices: List<WheelSlice> = emptyList()
        set(value) {
            field = value
            hover = null
            icons = value.map(::iconOf)
            layOut()
            invalidate()
        }
    private val actions: List<WheelSlice> get() = slices

    /** The name of the wheel showing, said in the middle while nothing is pointed at; empty for the top one. */
    var heading: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Settings › Wheel: a tick for each slice pointed at, icons without their names, the whole wheel scaled, and how dark the game is. */
    var tick = true
    var iconsOnly = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var scale = 1f
        set(value) {
            if (field == value) return
            field = value
            layOut()
            invalidate()
        }
    var dimPercent = 60
        set(value) {
            field = value
            dim.color = Color.argb(value.coerceIn(0, 100) * 255 / 100, 0, 0, 0)
            invalidate()
        }

    /** The line the wheel acts on when it is not on screen to be left undimmed (Recent lines): written over the ring. Empty for none. */
    var caption: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** What a slice says in the middle in place of what it does: whether a switch is on. Null for what it does. */
    var note: (WheelSlice) -> String? = { null }

    /** Why a slice cannot act now, or null when it can. A slice that cannot is dimmed, and says why in the middle. */
    var whyNot: (WheelSlice) -> String? = { null }
        set(value) {
            field = value
            invalidate()
        }
    private val parsed = HashMap<String, Path>()
    private var icons = slices.map(::iconOf)

    private fun iconOf(slice: WheelSlice): Path = parsed.getOrPut(slice.iconPath) { PathParser.createPathFromPathData(slice.iconPath) }

    private val dim = Paint().apply { color = Color.argb(153, 0, 0, 0) }
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
    private val glyph = text(22f).apply { textAlign = Paint.Align.CENTER }
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
        // It grows with its tiles, as the design's does, and never past the screen.
        val count = actions.size
        outer = minOf(context.dp(WheelSize.outer(count) * UNIT * scale).toFloat(), height * 0.46f, width * 0.36f)
        inner = outer * (WheelSize.inner(count) / WheelSize.outer(count))
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
        if (actions.isEmpty()) return
        val step = 360f / actions.size
        while (shapes.size < actions.size) shapes += Path()
        while (rims.size < actions.size) rims += Path()
        actions.indices.forEach { i ->
            val mid = -90f + i * step
            shapes[i].apply {
                rewind()
                oval.set(cx - outer, cy - outer, cx + outer, cy + outer)
                if (actions.size == 1) {
                    // One slice is the whole ring.
                    fillType = Path.FillType.EVEN_ODD
                    addCircle(cx, cy, outer, Path.Direction.CW)
                    addCircle(cx, cy, inner, Path.Direction.CW)
                } else {
                    fillType = Path.FillType.WINDING
                    arcTo(oval, mid - step / 2 + outerGap, step - 2 * outerGap, true)
                    oval.set(cx - inner, cy - inner, cx + inner, cy + inner)
                    arcTo(oval, mid + step / 2 - innerGap, -(step - 2 * innerGap))
                    close()
                }
            }
            rims[i].apply {
                rewind()
                oval.set(cx - rimRadius, cy - rimRadius, cx + rimRadius, cy + rimRadius)
                arcTo(oval, mid - step / 2 + rimGap, minOf(step - 2 * rimGap, 359.9f), true)
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

        if (actions.isEmpty()) {
            dashed.color = Ink.BORDER_STRONG
            canvas.drawCircle(cx, cy, (outer + inner) / 2, dashed)
            drawMiddle(canvas)
            return
        }
        val step = 360f / actions.size
        val middle = (outer + inner) / 2
        val iconSize = context.dp(22f).toFloat()
        // How wide a slice is where its name is written: a long name in a narrow slice is cut short.
        val room = if (actions.size == 1) outer else (2 * middle * sin(Math.toRadians(step / 2.0))).toFloat() - context.dp(10f)
        val lift = if (iconsOnly) 0f else context.dp(9f).toFloat()
        actions.indices.forEach { i ->
            val on = i == hover
            val off = whyNot(actions[i]) != null
            fill.color = if (on && !off) Ink.FILL else Ink.SURFACE
            edge.color = if (on && !off) Ink.FILL else Ink.BORDER_STRONG
            canvas.drawPath(shapes[i], fill)
            canvas.drawPath(shapes[i], edge)
            // A rim on a slice that opens a wheel, and on the one pointed at.
            val opens = actions[i].action == WheelAction.WHEEL || actions[i].action == WheelAction.BOXES
            if (on || opens) {
                rim.color = if (on) Ink.ACCENT else Ink.DIM
                canvas.drawPath(rims[i], rim)
            }
            val ink = when {
                off -> Ink.DIM
                on -> Ink.ON_FILL
                else -> Ink.TEXT
            }
            val angle = Math.toRadians((-90f + i * step).toDouble())
            val x = cx + middle * cos(angle).toFloat()
            val y = cy + middle * sin(angle).toFloat()
            // The icons are drawn on a 24-unit square, as the design's are.
            val slice = actions[i]
            if (slice.glyph.isNotEmpty()) {
                // The player's own emoji or character, in place of an icon.
                glyph.color = ink
                canvas.drawText(slice.glyph, x, y - lift - (glyph.ascent() + glyph.descent()) / 2, glyph)
            } else {
                val scale = iconSize / 24f
                iconMatrix.setScale(scale, scale)
                iconMatrix.postTranslate(x - iconSize / 2, y - iconSize / 2 - lift)
                icons[i].transform(iconMatrix, iconPath)
                icon.color = ink
                icon.strokeWidth = 1.9f * scale
                canvas.drawPath(iconPath, icon)
                // A box's icon is a frame with its number in it.
                if (slice.number.isNotEmpty()) {
                    number.color = ink
                    canvas.drawText(slice.number, x, y - lift - (number.ascent() + number.descent()) / 2, number)
                }
            }
            if (!iconsOnly) {
                label.color = ink
                val name = TextUtils.ellipsize(slice.label, label, room.coerceAtLeast(label.textSize * 2), TextUtils.TruncateAt.END).toString()
                canvas.drawText(name, x, y + context.dp(9f) + label.textSize * 0.8f, label)
            }
        }
        drawMiddle(canvas)
        if (caption.isNotEmpty()) {
            // Above the ring, or under it where there is no room above.
            about.color = Ink.TEXT
            about.textAlign = Paint.Align.CENTER
            val said = TextUtils.ellipsize(caption, about, width - context.dp(48f).toFloat(), TextUtils.TruncateAt.END).toString()
            val above = cy - outer - context.dp(22f)
            canvas.drawText(said, cx, if (above > about.textSize) above else cy + outer + context.dp(22f) + about.textSize, about)
            about.textAlign = Paint.Align.LEFT
        }
    }

    /** A box's number at its top left corner, and a dashed outline round a box not pointed at. */
    private fun drawBoxTag(canvas: Canvas, b: Box, slot: Int, on: Boolean, pad: Float, corner: Float) {
        if (!on && slot in always) {
            hole.color = Ink.DIM
            canvas.drawRoundRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad, corner, corner, hole)
        } else if (!on) {
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
        val why = chosen?.let(whyNot)
        val textWidth = (radius * 1.55f).toInt().coerceAtLeast(1)
        title.color = if (chosen == null) Ink.DIM else Ink.TEXT
        about.color = Ink.DIM
        val heading = chosen?.label ?: heading.ifBlank { "Point" }
        val name = StaticLayout.Builder.obtain(heading, 0, heading.length, title, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).build()
        val line = when {
            why != null -> why
            undrawn -> "Not drawn yet"
            chosen != null -> note(chosen) ?: chosen.about
            actions.isEmpty() -> "No tiles yet. Add some in Settings › Wheel."
            this.heading.isNotBlank() -> "Point with a stick"
            else -> "with a stick"
        }
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
        val angle = hover?.takeIf { actions.isNotEmpty() }?.let { Math.toRadians((-90.0 + it * 360.0 / actions.size)) }
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
        if (actions.isEmpty() || d < inner * 0.85f || d > outer + context.dp(36f)) return null
        return WheelAim.slice(x - cx, y - cy, actions.size, dead = 0f)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ownTap = event.x < CORNER_PX && event.y < CORNER_PX
        if (ownTap) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> sliceAt(event.x, event.y)?.let {
                if (hover != it) {
                    hover = it
                    onPoint()
                }
            }
            MotionEvent.ACTION_UP -> {
                val slice = sliceAt(event.x, event.y)
                when {
                    slice != null -> onPick(actions[slice])
                    hypot(event.x - cx, event.y - cy) < inner -> onBack()
                    else -> onDismiss()
                }
            }
        }
        return true
    }

    companion object {
        /** Where [OverlayHost]'s own tap lands, which the wheel leaves alone. */
        private const val CORNER_PX = 16

        /** How many dp one of the design's units is: its wheel of six tiles is this app's of 155 dp. */
        private const val UNIT = 155f / 182f
    }
}
