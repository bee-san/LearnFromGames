// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.core.BoxDrawing
import io.github.beesan.lfg.core.DialogueBox
import io.github.beesan.lfg.data.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import io.github.beesan.lfg.core.Box as ScreenBox

/** A game's last picture, decoded near the size it shows at, with the picture's own size its boxes are drawn in. */
class GamePicture(val image: ImageBitmap, val width: Int, val height: Int)

private fun decodePicture(path: String, showWidth: Int): GamePicture? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= showWidth) sample *= 2
    val bitmap: Bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return GamePicture(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight)
}

/** The picture at [path], loaded off the main thread; null while loading and when there is none. */
@Composable
private fun rememberPicture(path: String?, showWidth: Int): GamePicture? = produceState<GamePicture?>(null, path) {
    value = path?.let { withContext(Dispatchers.IO) { decodePicture(it, showWidth) } }
}.value

/** A box of the picture, drawn: solid in the accent for the one in hand, dashed for another. */
private fun DrawScope.drawBox(box: ScreenBox, picture: GamePicture, solid: Boolean) {
    val sx = size.width / picture.width
    val sy = size.height / picture.height
    val topLeft = Offset(box.left * sx, box.top * sy)
    val boxSize = Size(box.width * sx, box.height * sy)
    if (solid) {
        drawRect(Lfg.Accent.copy(alpha = 0.18f), topLeft, boxSize)
        drawRect(Lfg.Accent, topLeft, boxSize, style = Stroke(2.dp.toPx()))
    } else {
        drawRect(Color.White.copy(alpha = 0.8f), topLeft, boxSize, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
    }
}

/**
 * A game's boxes in Settings › Games and apps: box 1, the dialogue box the
 * game is read in by itself, then any others, each read when it is picked on
 * the wheel or, switched to, all the time beside box 1. Each is shown on the game's last picture, to draw, draw again
 * or take away, and more can be added.
 */
@Composable
fun GameBoxes(
    game: Game,
    first: DialogueBox?,
    others: List<DialogueBox>,
    picture: suspend (gameId: String) -> String?,
    onDraw: (Game, slot: Int) -> Unit,
    onRemove: (Game, slot: Int) -> Unit,
    onAlways: (Game, slot: Int, Boolean) -> Unit,
) {
    val path by produceState<String?>(null, game.id) { value = picture(game.id) }
    val shown = rememberPicture(path, 320)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Boxes", color = Lfg.Text, fontSize = 15.sp)
        BoxRow(
            game, 1, first, shown,
            if (first != null) "The dialogue box: read by itself when its text changes. Nothing outside it is read." else "The dialogue box. Not drawn: the whole screen is read.",
            onDraw, onRemove, removeLabel = "Read the whole screen",
        )
        others.forEachIndexed { i, box ->
            BoxRow(
                game, i + 2, box, shown, if (box.always) "Always read, beside box 1." else "Read when picked on the wheel.",
                onDraw, onRemove, removeLabel = "Remove",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always read", color = Lfg.Text, fontSize = 15.sp)
                    if (first == null) Text("Draw box 1 first", color = Lfg.Dim, fontSize = 13.sp)
                }
                OnOff(box.always) { onAlways(game, i + 2, it) }
            }
        }
        LfgButton("Add box…", { onDraw(game, others.size + 2) })
        // The wheel's Box tiles go by number: one removed in the middle moves the ones after it.
        if (others.size > 1) Text("Removing a box moves the ones after it up a number", color = Lfg.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun BoxRow(
    game: Game,
    slot: Int,
    box: DialogueBox?,
    picture: GamePicture?,
    detail: String,
    onDraw: (Game, Int) -> Unit,
    onRemove: (Game, Int) -> Unit,
    removeLabel: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (picture != null) {
            val shape = RoundedCornerShape(4.dp)
            Box(
                Modifier.width(128.dp).aspectRatio(picture.width.toFloat() / picture.height).clip(shape).border(1.dp, Lfg.Border, shape)
                    .semantics { contentDescription = if (box != null) "Box $slot on ${game.title}’s last picture" else "${game.title}’s last picture" },
            ) {
                Image(picture.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                if (box != null) Canvas(Modifier.fillMaxSize()) { drawBox(box.on(picture.width, picture.height), picture, solid = true) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Box $slot", color = Lfg.Text, fontSize = 15.sp)
            Text(detail, color = Lfg.Dim, fontSize = 13.sp)
        }
        LfgButton(if (box != null) "Redraw…" else "Draw…", { onDraw(game, slot) }, primary = box == null)
        if (box != null) LfgButton(removeLabel, { onRemove(game, slot) })
    }
}

/**
 * A game's box [slot] drawn on the game's last picture, [path], as over the
 * game: two corners, each with a tap, a drag across, or the D-pad and the
 * confirm button. The second corner saves it. The game's other boxes are
 * shown dashed.
 */
@Composable
fun BoxDrawScreen(
    game: Game,
    slot: Int,
    path: String?,
    others: List<DialogueBox>,
    swapConfirm: Boolean,
    onSave: (DialogueBox) -> Unit,
    onBack: () -> Unit,
) {
    val picture = rememberPicture(path, 1280)
    val drawing = remember(picture) { picture?.let { BoxDrawing(it.width, it.height) } }
    // The drawing is not Compose state: this is bumped to draw it again.
    var drawn by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val confirmKey = if (swapConfirm) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
    val backKey = if (swapConfirm) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
    val confirm = if (swapConfirm) "B" else "A"
    val back = if (swapConfirm) "A" else "B"

    /** Drawn again; and, with the second corner set, saved. */
    fun changed() {
        drawn++
        val d = drawing ?: return
        val p = picture ?: return
        if (d.stage == BoxDrawing.Stage.DONE) d.box?.let { onSave(DialogueBox(it, p.width, p.height)) }
    }

    Page("Draw box $slot", onBack) {
        when {
            path == null -> Text(
                "No picture of ${game.title} is kept to draw on. Pick Box $slot on the wheel over the game to draw it there.",
                color = Lfg.Dim, fontSize = 15.sp,
            )
            picture == null || drawing == null -> Text("Loading…", color = Lfg.Dim, fontSize = 15.sp)
            else -> {
                LaunchedEffect(drawing) { focus.requestFocus() }
                val step = maxOf(1, picture.width / 120)
                Text(
                    "${game.title}’s last picture. Tap two corners, or drag across. With the controller, move to a corner and press $confirm, twice.",
                    color = Lfg.Dim, fontSize = 14.sp,
                )
                Box(
                    Modifier.fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height).clip(RoundedCornerShape(4.dp))
                        .semantics { contentDescription = "${game.title}’s last picture, to draw box $slot on" }
                        .focusRequester(focus)
                        .onKeyEvent { event ->
                            val down = event.type == KeyEventType.KeyDown
                            when (event.key.nativeKeyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) drawing.move(-step, 0)
                                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) drawing.move(step, 0)
                                KeyEvent.KEYCODE_DPAD_UP -> if (down) drawing.move(0, -step)
                                KeyEvent.KEYCODE_DPAD_DOWN -> if (down) drawing.move(0, step)
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, confirmKey -> if (down) drawing.confirm()
                                // On release, as over the game. With no corner to take back, it leaves.
                                backKey -> if (!down && !drawing.back()) onBack()
                                else -> return@onKeyEvent false
                            }
                            changed()
                            true
                        }
                        .focusable()
                        .pointerInput(drawing) {
                            fun toPicture(at: Offset) = (at.x / size.width * picture.width).roundToInt() to (at.y / size.height * picture.height).roundToInt()
                            awaitEachGesture {
                                val first = awaitFirstDown()
                                first.consume()
                                toPicture(first.position).let { (x, y) -> drawing.touchDown(x, y) }
                                changed()
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    toPicture(change.position).let { (x, y) -> drawing.touchMove(x, y) }
                                    changed()
                                }
                                drawing.touchUp()
                                changed()
                            }
                        },
                ) {
                    Image(picture.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize()) {
                        // Read here so that every change draws again.
                        if (drawn < 0) return@Canvas
                        others.forEach { drawBox(it.on(picture.width, picture.height), picture, solid = false) }
                        drawing.box?.let { drawBox(it, picture, solid = true) }
                        // The cursor: where the next corner goes.
                        if (drawing.stage != BoxDrawing.Stage.DONE) {
                            val c = Offset(drawing.x * size.width / picture.width, drawing.y * size.height / picture.height)
                            val arm = 10.dp.toPx()
                            for ((ink, width) in listOf(Color.Black.copy(alpha = 0.8f) to 5.dp.toPx(), Color.White to 2.dp.toPx())) {
                                drawLine(ink, c - Offset(arm, 0f), c + Offset(arm, 0f), width)
                                drawLine(ink, c - Offset(0f, arm), c + Offset(0f, arm), width)
                            }
                        }
                    }
                }
                Text("$back takes a corner back, or leaves.", color = Lfg.Dim, fontSize = 13.sp)
            }
        }
    }
}
