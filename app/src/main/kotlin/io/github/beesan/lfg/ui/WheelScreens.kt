// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.Wheels
import io.github.beesan.lfg.core.WheelAction
import io.github.beesan.lfg.core.WheelAim
import io.github.beesan.lfg.core.WheelGroup
import io.github.beesan.lfg.core.WheelIcons
import io.github.beesan.lfg.core.WheelSet
import io.github.beesan.lfg.core.WheelSize
import io.github.beesan.lfg.core.WheelSlice
import io.github.beesan.lfg.data.Game
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// ── Settings › Wheel ─────────────────────────────────────────────────────────

@Composable
private fun Heading(text: String) = Text(text, color = Lfg.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))

private fun tiles(count: Int) = if (count == 1) "1 tile" else "$count tiles"

/**
 * The wheel's settings: which wheel a game uses and the way to edit, copy
 * and reset it, then how it opens, how it is pointed at, what a pick leaves
 * and how it looks. [chosen] is the game whose wheel the first rows are
 * about, [Wheels.SHARED] for the shared one.
 */
@Composable
fun WheelSettingsScreen(
    prefs: Prefs,
    onChange: ((Prefs) -> Prefs) -> Unit,
    sets: Map<String, WheelSet>,
    /** The games a wheel can be given to, the one played last first. */
    games: List<Game>,
    chosen: String,
    onChoose: (String) -> Unit,
    message: String?,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onReset: () -> Unit,
    onControls: () -> Unit,
    onBack: () -> Unit,
) = Page("Wheel", onBack) {
    val game = games.firstOrNull { it.id == chosen }?.takeIf { prefs.wheelPerGame }
    val own = game != null && game.id in sets
    val set = (if (own) sets[chosen] else null) ?: sets[Wheels.SHARED] ?: WheelSet.DEFAULT
    Heading("Wheel")
    Card("Wheel for", "A game can have its own wheel. Others use the shared one.") {
        Choice(listOf("all" to "All games", "each" to "Each game its own"), if (prefs.wheelPerGame) "each" else "all") { v ->
            onChange { it.copy(wheelPerGame = v == "each") }
        }
    }
    if (prefs.wheelPerGame) {
        val ids = listOf(Wheels.SHARED) + games.map { it.id }
        val at = ids.indexOf(game?.id ?: Wheels.SHARED).coerceAtLeast(0)
        Card(
            "Whose wheel",
            when {
                game == null -> "The shared wheel, of every game without its own."
                own -> "${game.title} has its own wheel."
                else -> "${game.title} uses the shared wheel until its own is changed."
            },
        ) {
            LfgButton("◀", { onChoose(ids[Math.floorMod(at - 1, ids.size)]) }, modifier = Modifier.semantics { contentDescription = "The wheel before" })
            Text(
                game?.title ?: "Shared", color = Lfg.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(min = 112.dp, max = 220.dp),
            )
            LfgButton("▶", { onChoose(ids[Math.floorMod(at + 1, ids.size)]) }, modifier = Modifier.semantics { contentDescription = "The next wheel" })
        }
    }
    Card("Edit wheel…", tiles(set.top.slices.size) + (set.wheels.size - 1).let { if (it > 0) ", and " + (if (it == 1) "1 wheel" else "$it wheels") + " inside" else "" }, onClick = onEdit)
    Card("Copy wheel", message ?: "Save the wheel as a file, or load one saved before.") {
        LfgButton("Save as…", onSave)
        LfgButton("Load…", onLoad)
    }
    Card("Reset wheel", if (game != null) "Back to the shared wheel." else "Back to the wheel LFG starts with.") {
        LfgButton("Reset", onReset, enabled = if (game != null) own else Wheels.SHARED in sets)
    }

    Heading("Opening")
    Card("Lookup shortcut", "Tap for lookup, hold for the wheel. Set in Controls.") { LfgButton(shortcutLabel(prefs), onControls) }
    Card("Hold for the wheel", "How long the shortcut is held before the wheel opens.") {
        Stepper("${prefs.wheelHoldMs} ms", prefs.wheelHoldMs, Prefs.WHEEL_HOLD_STEPS) { v -> onChange { it.copy(wheelHoldMs = v) } }
    }
    Card("Open wheels while held", "Pointing at a wheel tile opens it at once, so letting go picks inside it.") {
        OnOff(prefs.wheelOpenHeld) { v -> onChange { it.copy(wheelOpenHeld = v) } }
    }

    Heading("Pointing")
    Card("D-pad", "Point: each way is a slice, up to 8. Step: ◀ ▶ go round a wheel of any size.") {
        Choice(listOf(Prefs.WHEEL_POINT to "Point", Prefs.WHEEL_STEP to "Step round"), prefs.wheelDpad) { v -> onChange { it.copy(wheelDpad = v) } }
    }
    Card("Stick reach", "How far the stick is pushed before it points.") {
        Stepper("${prefs.wheelReach}%", prefs.wheelReach, Prefs.WHEEL_REACH_STEPS) { v -> onChange { it.copy(wheelReach = v) } }
    }
    Card("Vibrate on each slice") { OnOff(prefs.wheelTick) { v -> onChange { it.copy(wheelTick = v) } } }
    Card("Start on the last pick", "Each wheel opens pointing at the slice picked last.") {
        OnOff(prefs.wheelStartLast) { v -> onChange { it.copy(wheelStartLast = v) } }
    }

    Heading("After picking")
    Card("Wheel after a pick", "Stay open is handy for Keep or Copy several times.") {
        Choice(listOf("close" to "Close", "stay" to "Stay open"), if (prefs.wheelStay) "stay" else "close") { v -> onChange { it.copy(wheelStay = v == "stay") } }
    }

    Heading("Look")
    Card("Slices") {
        Choice(listOf("names" to "Names and icons", "icons" to "Icons only"), if (prefs.wheelIconsOnly) "icons" else "names") { v ->
            onChange { it.copy(wheelIconsOnly = v == "icons") }
        }
    }
    Card("Wheel size", "The wheel grows with its tiles. This scales the whole of it.") {
        Stepper("${prefs.wheelSize}%", prefs.wheelSize, Prefs.WHEEL_SIZE_STEPS) { v -> onChange { it.copy(wheelSize = v) } }
    }
    Card("Dim the game") {
        Stepper("${prefs.wheelDim}%", prefs.wheelDim, Prefs.WHEEL_DIM_STEPS) { v -> onChange { it.copy(wheelDim = v) } }
    }
}

// ── Edit wheel ───────────────────────────────────────────────────────────────

/** The icons' paths, parsed once each. Main thread only, as drawing is. */
private val iconPaths = HashMap<String, Path>()

private fun iconPath(data: String): Path = iconPaths.getOrPut(data) { PathParser.createPathFromPathData(data).asComposePath() }

/** One of the wheel's icons: strokes on a 24-unit square, drawn at [size]. */
@Composable
private fun WheelIcon(data: String, tint: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawPath(iconPath(data), tint, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A point of the wheel: [r] from the middle at [c], [degrees] clockwise from straight up. */
private fun polar(c: Float, r: Float, degrees: Float): Offset {
    val t = Math.toRadians(degrees.toDouble())
    return Offset(c + r * sin(t).toFloat(), c - r * cos(t).toFloat())
}

private fun gapDegrees(pixels: Float, radius: Float) = Math.toDegrees((pixels / radius).toDouble()).toFloat()

/** A slice from [a0] to [a1], with a gap of the same few units between it and the next all the way along. */
private fun slicePath(c: Float, outer: Float, inner: Float, a0: Float, a1: Float) = Path().apply {
    val go = gapDegrees(1.5f, outer)
    val gi = gapDegrees(1.5f, inner)
    arcTo(Rect(Offset(c, c), outer), a0 + go - 90f, (a1 - a0) - 2 * go, forceMoveTo = true)
    arcTo(Rect(Offset(c, c), inner), a1 - gi - 90f, -((a1 - a0) - 2 * gi), forceMoveTo = false)
    close()
}

/** How a wheel of [count] slices is laid out, in the design's units. */
private class WheelShape(val count: Int) {
    val outer = WheelSize.outer(count)
    val inner = WheelSize.inner(count)
    val size = 2 * (outer + PAD)
    val c = size / 2
    val step = if (count > 0) 360f / count else 360f
    val mid = (outer + inner) / 2

    /** The slice at ([x], [y]) of a picture [side] pixels across, or null off the ring. */
    fun sliceAt(x: Float, y: Float, side: Float): Int? {
        if (count == 0 || side <= 0f) return null
        val dx = x * size / side - c
        val dy = y * size / side - c
        val d = hypot(dx, dy)
        if (d < inner || d > outer + 10f) return null
        return WheelAim.slice(dx, dy, count, dead = 0f)
    }

    companion object {
        const val PAD = 28f
    }
}

/**
 * A wheel as it is edited: its slices with their icons and names, the one
 * selected filled, a rim on those that open a wheel, its name and size in the
 * middle. While a tile is dragged over it ([dragging]), the slice under it
 * ([over]) is lit and a mark shows where the tile would go.
 */
@Composable
private fun WheelPicture(
    name: String,
    slices: List<WheelSlice>,
    selected: Int?,
    over: Int?,
    dragging: Boolean,
    side: Dp,
    onTap: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = remember(slices.size) { WheelShape(slices.size) }
    val tap by rememberUpdatedState(onTap)
    val open by rememberUpdatedState(onOpen)
    val label = remember { TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val plain = remember { TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val surface = Lfg.Surface
    val fill = Lfg.Fill
    val wash = Lfg.Wash
    val accent = Lfg.Accent
    val strong = Lfg.BorderStrong
    val text = Lfg.Text
    val dim = Lfg.Dim
    val onFill = Lfg.OnFill
    val bg = Lfg.Bg
    Canvas(
        modifier.size(side)
            .semantics { contentDescription = "$name, ${tiles(slices.size)}" }
            .pointerInput(shape) {
                detectTapGestures(
                    onTap = { at -> shape.sliceAt(at.x, at.y, size.width.toFloat())?.let { tap(it) } },
                    onDoubleTap = { at -> shape.sliceAt(at.x, at.y, size.width.toFloat())?.let { open(it) } },
                )
            },
    ) {
        val n = shape.count
        val c = shape.c
        scale(size.width / shape.size, pivot = Offset.Zero) {
            if (n == 0) {
                drawCircle(strong, shape.mid, Offset(c, c), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
            }
            slices.forEachIndexed { i, slice ->
                val on = i == selected
                val lit = dragging && i == over
                val a0 = -shape.step / 2 + i * shape.step
                val path = slicePath(c, shape.outer, shape.inner, a0, a0 + shape.step)
                drawPath(path, if (on) fill else if (lit) wash else surface)
                drawPath(path, if (on) fill else if (lit) accent else strong, style = Stroke(1f))
                if (slice.action == WheelAction.WHEEL || slice.action == WheelAction.BOXES) {
                    val r = shape.outer + 7f
                    val g = gapDegrees(6f, r)
                    drawArc(
                        if (on) accent else dim, a0 + g - 90f, shape.step - 2 * g, useCenter = false,
                        topLeft = Offset(c - r, c - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                        style = Stroke(3f, cap = StrokeCap.Round),
                    )
                }
                val ink = if (on) onFill else text
                val p = polar(c, shape.mid, i * shape.step)
                // The room a name has across its slice, where it is written.
                val room = if (n <= 2) shape.outer - shape.inner else 2 * (shape.mid + 14f) * sin(Math.toRadians(shape.step / 2.0)).toFloat() - 10f
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    if (slice.glyph.isNotEmpty()) {
                        plain.color = ink.toArgb()
                        plain.textSize = 22f
                        native.drawText(slice.glyph, p.x, p.y + 1f, plain)
                    } else if (slice.number.isNotEmpty()) {
                        label.color = ink.toArgb()
                        label.textSize = 11f
                        native.drawText(slice.number, p.x, p.y - 5f, label)
                    }
                    label.color = ink.toArgb()
                    label.textSize = 13.5f
                    // A crowded wheel: the name smaller, then cut short.
                    val wide = label.measureText(slice.label)
                    if (wide > room) label.textSize = (13.5f * room / wide).coerceAtLeast(9.5f)
                    native.drawText(TextUtils.ellipsize(slice.label, label, room.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString(), p.x, p.y + 20f, label)
                }
                if (slice.glyph.isEmpty()) {
                    withTransform({
                        translate(p.x - 11f, p.y - 20f)
                        scale(22f / 24f, 22f / 24f, pivot = Offset.Zero)
                    }) { drawPath(iconPath(slice.iconPath), ink, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                }
            }
            if (dragging && n > 0) {
                val at = -shape.step / 2 + ((over ?: (n - 1)) + 1) * shape.step
                drawLine(accent, polar(c, shape.inner - 4f, at), polar(c, shape.outer + 4f, at), strokeWidth = 4f, cap = StrokeCap.Round)
            }
            drawCircle(bg, shape.inner - 8f, Offset(c, c))
            drawCircle(strong, shape.inner - 8f, Offset(c, c), style = Stroke(1f))
            drawIntoCanvas { canvas ->
                val room = 2 * (shape.inner - 8f) - 16f
                label.color = text.toArgb()
                label.textSize = 18f
                canvas.nativeCanvas.drawText(TextUtils.ellipsize(name, label, room, TextUtils.TruncateAt.END).toString(), c, c - 2f, label)
                plain.color = dim.toArgb()
                plain.textSize = 12f
                canvas.nativeCanvas.drawText(if (n > 0) tiles(n) else "Drop tiles here", c, c + 18f, plain)
            }
        }
    }
}

/** A tile being dragged from the list: which, and where the finger is, in the window. */
private data class Dragged(val action: WheelAction, val at: Offset)

/** A one-line field of the edit bar or the icon pane. */
@Composable
private fun BarField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "", size: Int = 15) {
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Lfg.Text, fontSize = size.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(Lfg.Accent),
        modifier = modifier.heightIn(min = 44.dp).padField(shape, singleLine = true).clip(shape).background(Lfg.Sunken).border(1.dp, Lfg.BorderStrong, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { inner ->
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = Lfg.Dim, fontSize = size.sp, maxLines = 1)
            inner()
        },
    )
}

/**
 * Where a wheel is edited: the wheel, the tiles to add to it, and what can be
 * done to the slice selected. Wheels inside it are opened and edited the same
 * way. Every change is handed to [onChange] at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WheelEditScreen(
    set: WheelSet,
    /** The game whose own wheel this is, or empty for the shared one. */
    whose: String,
    onChange: (WheelSet) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var path by rememberSaveable { mutableStateOf(listOf(WheelSet.TOP)) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Dragged?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var wheelBounds by remember { mutableStateOf(Rect.Zero) }

    // A wheel that has gone (reset, or its slice removed) is left for the nearest one still there.
    val trail = path.takeWhile { set[it] != null }.ifEmpty { listOf(WheelSet.TOP) }
    val here = trail.last()
    val slices = set.level(here)
    val name = set[here]?.name?.ifBlank { "Wheel" } ?: "Wheel"
    val at = selected?.takeIf { it in slices.indices }
    val slice = at?.let { slices[it] }
    val iconsOpen = picking && slice != null
    val shape = remember(slices.size) { WheelShape(slices.size) }

    fun go(to: List<String>) {
        path = to
        selected = null
        picking = false
    }
    fun add(action: WheelAction, where: Int? = null) {
        val (next, index) = set.add(here, action, where)
        if (index < 0) return
        onChange(next)
        selected = index
        adding = false
    }
    fun edit(change: (WheelSlice) -> WheelSlice) = at?.let { onChange(set.edit(here, it, change)) }
    fun open(index: Int) = slices.getOrNull(index)?.takeIf { it.action == WheelAction.WHEEL && set[it.wheel] != null }?.let { go(trail + it.wheel) }

    /** The slice under a tile being dragged, or null off the ring. */
    fun overAt(point: Offset): Int? =
        if (wheelBounds.contains(point)) shape.sliceAt(point.x - wheelBounds.left, point.y - wheelBounds.top, wheelBounds.width) else null

    BackHandler(enabled = iconsOpen || adding || trail.size > 1) {
        when {
            iconsOpen -> picking = false
            adding -> adding = false
            else -> go(trail.dropLast(1))
        }
    }

    @Composable
    fun Crumbs() {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Wheels" }) {
            trail.forEachIndexed { i, id ->
                val last = i == trail.lastIndex
                if (i > 0) Text("›", color = Lfg.Dim, fontSize = 15.sp)
                Text(
                    set[id]?.name?.ifBlank { "Wheel" } ?: "Wheel",
                    color = if (last) Lfg.Text else Lfg.Accent, fontSize = 15.sp, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.focusRing().clip(RoundedCornerShape(6.dp)).clickable(enabled = !last) { go(trail.take(i + 1)) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }
    }

    @Composable
    fun Header(narrow: Boolean) {
        val count = tiles(slices.size) + if (whose.isNotEmpty()) " · $whose" else ""
        if (narrow) {
            // One row that scrolls sideways, so the wheel under it keeps the height of a small screen.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                LfgButton("Back", onBack)
                LfgButton("Add tile", { adding = true; picking = false }, primary = true)
                Crumbs()
                Text(count, color = Lfg.Dim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
                LfgButton("Reset", { onReset(); go(listOf(WheelSet.TOP)) })
            }
            return
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Edit wheel", color = Lfg.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Crumbs()
            Text(count, color = Lfg.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            LfgButton("Reset", { onReset(); go(listOf(WheelSet.TOP)) })
            LfgButton("Back", onBack)
        }
    }

    @Composable
    fun Bar(narrow: Boolean = false) {
        if (slice == null || at == null) {
            Text(
                "Select a slice to move or remove it. Open a wheel slice to edit the wheel inside it.", color = Lfg.Dim, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
            return
        }

        @Composable
        fun Info(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (slice.action) {
                    WheelAction.BOX -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Box", color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        LfgButton("−", { edit { it.copy(box = (it.box - 1).coerceAtLeast(1)) } }, modifier = Modifier.semantics { contentDescription = "Lower box number" })
                        Text(slice.box.toString(), color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 20.dp))
                        LfgButton("+", { edit { it.copy(box = (it.box + 1).coerceAtMost(WheelSet.MAX_BOX)) } }, modifier = Modifier.semantics { contentDescription = "Raise box number" })
                    }
                    WheelAction.WHEEL -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Name", color = Lfg.Dim, fontSize = 13.sp)
                        BarField(set[slice.wheel]?.name.orEmpty(), { onChange(set.rename(slice.wheel, it)) }, Modifier.width(180.dp))
                    }
                    else -> Text(slice.action.label, color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                if (slice.action == WheelAction.PROMPT) {
                    BarField(slice.prompt, { typed -> edit { it.copy(prompt = typed.take(WheelSet.PROMPT_LENGTH)) } }, Modifier.fillMaxWidth(), hint = "The question to ask of a line")
                } else {
                    Text(
                        when {
                            slice.action == WheelAction.WHEEL -> "Opens a wheel of " + tiles(set[slice.wheel]?.slices?.size ?: 0)
                            slice.action == WheelAction.BOX && slice.box > 1 -> "Read box ${slice.box}. In a game without it, nothing happens."
                            else -> slice.about
                        },
                        color = Lfg.Dim, fontSize = 13.sp, maxLines = if (narrow) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        @Composable
        fun Buttons() {
            if (slice.action == WheelAction.WHEEL) LfgButton("Open", { open(at) }, primary = true)
            LfgButton("Change icon…", { picking = true; adding = false })
            LfgButton("↺", { set.move(here, at, -1).let { (next, to) -> onChange(next); selected = to } }, modifier = Modifier.semantics { contentDescription = "Move back" })
            LfgButton("↻", { set.move(here, at, 1).let { (next, to) -> onChange(next); selected = to } }, modifier = Modifier.semantics { contentDescription = "Move on" })
            LfgButton("Remove", { onChange(set.remove(here, at)); selected = null; picking = false })
        }
        if (narrow) {
            // One row that scrolls sideways, under the wheel, as the header is over it.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Info(Modifier.width(if (slice.action == WheelAction.PROMPT || slice.action == WheelAction.WHEEL) 250.dp else 190.dp))
                Buttons()
            }
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Info(Modifier.weight(1f).widthIn(min = 180.dp))
                Buttons()
            }
        }
    }

    @Composable
    fun TilesPane(draggable: Boolean, modifier: Modifier) {
        Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Tiles", color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (draggable) "Hold and drag onto the wheel, or tap to add" else "Tap to add", color = Lfg.Dim, fontSize = 13.sp)
                }
                if (!draggable) LfgButton("Done", { adding = false })
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (group in WheelGroup.entries) {
                    Text(group.label, color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    for (row in WheelAction.entries.filter { it.group == group }.chunked(4)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (action in row) Tile(action, draggable, held = drag?.action == action, Modifier.weight(1f), onAdd = { add(action) }, onDrag = { drag = Dragged(action, it) }, onDrop = {
                                drag?.let { d -> if (wheelBounds.contains(d.at)) add(d.action, overAt(d.at)?.plus(1)) }
                                drag = null
                            }, onCancel = { drag = null })
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun IconsPane(modifier: Modifier) {
        val current = slice ?: return
        Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Icon for ${current.label}", color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                LfgButton("Done", { picking = false }, primary = true)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Emoji or character", color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                BarField(
                    current.glyph,
                    { typed -> WheelIcons.lastGlyph(typed).let { g -> edit { if (g.isEmpty()) it.copy(glyph = "") else it.copy(glyph = g, icon = "") } } },
                    Modifier.fillMaxWidth(), hint = "あ", size = 20,
                )
                for ((heading, icons) in listOf("LFG" to WheelIcons.LFG, "More" to WheelIcons.MORE)) {
                    Text(heading, color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    for (row in icons.entries.chunked(7)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((key, data) in row) {
                                val on = current.icon == key && current.glyph.isEmpty()
                                val corners = RoundedCornerShape(10.dp)
                                Box(
                                    Modifier.weight(1f).height(48.dp).focusRing(corners).clip(corners).background(if (on) Lfg.Fill else Lfg.Sunken)
                                        .border(1.dp, if (on) Lfg.Fill else Lfg.Border, corners)
                                        .clickable { edit { it.copy(icon = key, glyph = "") } }
                                        .semantics { contentDescription = key },
                                    contentAlignment = Alignment.Center,
                                ) { WheelIcon(data, if (on) Lfg.OnFill else Lfg.Text) }
                            }
                            repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (current.custom) LfgButton("Reset icon", { edit { it.copy(icon = "", glyph = "") } }, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    @Composable
    fun Picture(side: Dp) = WheelPicture(
        name, slices, at, drag?.let { overAt(it.at) }, drag != null, side,
        onTap = { i ->
            if (selected == i) {
                selected = null
                picking = false
            } else {
                selected = i
            }
        },
        onOpen = { open(it) },
        modifier = Modifier.onGloballyPositioned { wheelBounds = it.boundsInRoot() },
    )

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val full = shape.size.dp
        val whole = maxWidth
        if (maxWidth < TWO_PANES) {
            // One pane at a time: the wheel with its bar, the tiles, or the icons.
            when {
                iconsOpen -> IconsPane(Modifier.fillMaxSize())
                adding -> TilesPane(draggable = false, Modifier.fillMaxSize())
                else -> Column(Modifier.fillMaxSize()) {
                    Header(narrow = true)
                    HorizontalDivider(color = Lfg.Border)
                    // The wheel as big as the room between the two rows, whole on a small screen.
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Picture(minOf(full, maxWidth, maxHeight))
                    }
                    HorizontalDivider(color = Lfg.Border)
                    Bar(narrow = true)
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Header(narrow = false)
                    HorizontalDivider(color = Lfg.Border)
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Picture(minOf(full, maxWidth, maxHeight))
                    }
                    HorizontalDivider(color = Lfg.Border)
                    // One row, as on a small screen: a bar that wrapped would take the wheel's room as a slice is selected.
                    Bar(narrow = true)
                }
                val pane = Modifier.width(minOf(456.dp, whole * 0.38f)).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Lfg.Surface).padding(14.dp)
                if (iconsOpen) IconsPane(pane) else TilesPane(draggable = true, pane)
            }
        }
        // The tile under the finger while it is dragged.
        drag?.let { d ->
            val density = LocalDensity.current
            val half = with(density) { 32.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((d.at.x - origin.x - half).roundToInt(), (d.at.y - origin.y - half * 1.6f).roundToInt()) }.size(64.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Lfg.Wash).border(1.dp, Lfg.Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { WheelIcon(WheelIcons.ALL.getValue(d.action.id), Lfg.Text) }
        }
    }
}

/** A tile in the list of tiles: a tap adds it; held and dragged, it goes where it is dropped on the wheel. */
@Composable
private fun Tile(
    action: WheelAction,
    draggable: Boolean,
    held: Boolean,
    modifier: Modifier,
    onAdd: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    val corners = RoundedCornerShape(12.dp)
    var place by remember { mutableStateOf(Offset.Zero) }
    val dragTo by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    Column(
        modifier.height(84.dp).onGloballyPositioned { place = it.positionInRoot() }
            .then(
                if (draggable) Modifier.pointerInput(action) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragTo(place + it) },
                        onDrag = { change, _ ->
                            change.consume()
                            dragTo(place + change.position)
                        },
                        onDragEnd = { drop() },
                        onDragCancel = { cancel() },
                    )
                } else Modifier,
            )
            .focusRing(corners).clip(corners).background(Lfg.Sunken).border(1.dp, if (held) Lfg.Accent else Lfg.Border, corners)
            .clickable(onClick = onAdd).semantics { contentDescription = action.label + ". " + action.more }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        WheelIcon(WheelIcons.ALL.getValue(action.id), Lfg.Text)
        Text(action.label, color = Lfg.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Narrower than this, the wheel and the tiles do not fit side by side. */
private val TWO_PANES = 700.dp
