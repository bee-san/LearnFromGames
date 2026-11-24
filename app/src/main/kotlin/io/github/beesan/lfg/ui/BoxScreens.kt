// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.core.Box as ScreenBox
import io.github.beesan.lfg.core.DialogueBox
import io.github.beesan.lfg.data.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
 * A game's two boxes in Settings › Games and apps: box 1, which the game is
 * read in by itself, and box 2, read when it is picked on the wheel. Each is
 * shown on the game's last picture, to draw, draw again or take away.
 */
@Composable
fun GameBoxes(
    game: Game,
    first: DialogueBox?,
    second: DialogueBox?,
    picture: suspend (gameId: String) -> String?,
    onDraw: (Game, slot: Int) -> Unit,
    onRemove: (Game, slot: Int) -> Unit,
) {
    val path by produceState<String?>(null, game.id) { value = picture(game.id) }
    val shown = rememberPicture(path, 320)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Boxes", color = Lfg.Text, fontSize = 15.sp)
        BoxRow(
            game, 1, first, shown,
            if (first != null) "Read by itself when its text changes. Nothing outside it is read." else "Not drawn: the whole screen is read.",
            onDraw, onRemove, removeLabel = "Read the whole screen",
        )
        BoxRow(
            game, 2, second, shown,
            if (second != null) "Read only when picked on the wheel." else "Not drawn. Read only when picked on the wheel.",
            onDraw, onRemove, removeLabel = "Remove",
        )
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
 * A game's box [slot] drawn with a finger on the game's last picture, [path]:
 * the other box is shown dashed, so the two are told apart.
 */
@Composable
fun BoxDrawScreen(
    game: Game,
    slot: Int,
    path: String?,
    current: DialogueBox?,
    other: DialogueBox?,
    onSave: (DialogueBox) -> Unit,
    onBack: () -> Unit,
) {
    val picture = rememberPicture(path, 1280)
    // The box in the picture's pixels: the one kept, until a new one is drawn.
    var drawn by remember(picture) { mutableStateOf(picture?.let { p -> current?.on(p.width, p.height) }) }
    var start by remember { mutableStateOf<Offset?>(null) }
    val big = drawn?.let { it.width >= DialogueBox.MIN && it.height >= DialogueBox.MIN } == true
    Page(
        "Draw box $slot", onBack,
        actions = {
            LfgButton("Save", { picture?.let { p -> drawn?.let { onSave(DialogueBox(it, p.width, p.height)) } } }, primary = true, enabled = picture != null && big)
        },
    ) {
        when {
            path == null -> Text(
                "No picture of ${game.title} is kept to draw on. Pick Box $slot on the wheel over the game to draw it there.",
                color = Lfg.Dim, fontSize = 15.sp,
            )
            picture == null -> Text("Loading…", color = Lfg.Dim, fontSize = 15.sp)
            else -> {
                Text("${game.title}’s last picture. Drag a box around the text.", color = Lfg.Dim, fontSize = 14.sp)
                Box(
                    Modifier.fillMaxWidth().aspectRatio(picture.width.toFloat() / picture.height).clip(RoundedCornerShape(4.dp))
                        .semantics { contentDescription = "${game.title}’s last picture, to draw box $slot on" }
                        .pointerInput(picture) {
                            fun toPicture(at: Offset) = (at.x / size.width * picture.width).roundToInt().coerceIn(0, picture.width) to
                                (at.y / size.height * picture.height).roundToInt().coerceIn(0, picture.height)
                            detectDragGestures(
                                onDragStart = { at -> start = at },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val (x0, y0) = toPicture(start ?: change.position)
                                    val (x1, y1) = toPicture(change.position)
                                    drawn = ScreenBox(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
                                },
                                onDragEnd = { start = null },
                            )
                        },
                ) {
                    Image(picture.image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize()) {
                        other?.let { drawBox(it.on(picture.width, picture.height), picture, solid = false) }
                        drawn?.let { drawBox(it, picture, solid = true) }
                    }
                }
                drawn?.let { Text("${it.width} × ${it.height}" + if (!big) " · Too small to hold a line" else "", color = Lfg.Dim, fontSize = 13.sp) }
            }
        }
    }
}
