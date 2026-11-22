// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.content.Context
import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Palette
import kotlinx.coroutines.launch

/**
 * The app's colours for Compose, from the [Palette] Settings picked. Each
 * reads [palette], which the activity sets, so every screen redraws when the
 * scheme or the light or dark theme changes.
 */
object Lfg {
    var palette: Palette by mutableStateOf(Palette.of(Palette.PINK, Palette.THEME_DARK, systemDark = true))

    val Bg get() = Color(palette.bg)
    val Surface get() = Color(palette.surface)
    val Sunken get() = Color(palette.sunken)
    val Border get() = Color(palette.border)
    val BorderStrong get() = Color(palette.borderStrong)
    val Text get() = Color(palette.text)
    val Dim get() = Color(palette.dim)
    val Accent get() = Color(palette.accent)
    val Fill get() = Color(palette.fill)
    val Soft get() = Color(palette.soft)
    val OnFill get() = Color(palette.onFill)
    val Wash get() = Color(palette.wash)
    val Ok get() = Color(palette.ok)
    val OkSoft get() = Color(palette.okSoft)
    val Warn get() = Color(palette.warn)
    val Error get() = Color(palette.error)
}

/** The palette for these settings, with the system's light or dark setting for "Match system". */
fun Context.palette(prefs: Prefs): Palette = Palette.of(
    prefs.colours,
    prefs.theme,
    systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
)

/**
 * A ring in the accent colour round whatever has the controller's focus,
 * drawn a little outside it so a filled button's own colour cannot hide it.
 * Put it before the `clickable` (or the button) whose focus it shows. A touch
 * focuses nothing, so the ring shows only for the pad and keys.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(8.dp), inside: Boolean = false, orInside: Boolean = false): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val view = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val margin = with(LocalDensity.current) { 12.dp.toPx() }
    bringIntoViewRequester(view).onSizeChanged { size = it }.onFocusChanged {
        // With [orInside], focus on something within (a text field inside its frame) shows it too.
        focused = if (orInside) it.hasFocus else it.isFocused
        // A scrolling page brings the control itself into view; this brings its ring and a little room too.
        if (it.isFocused) scope.launch { view.bringIntoView(Rect(-margin, -margin, size.width + margin, size.height + margin)) }
    }.drawWithContent {
        drawContent()
        if (focused) {
            val stroke = 3.dp.toPx()
            // Inside, for rows of a list, whose edges the list cuts off.
            val out = if (inside) -stroke / 2 else 2.dp.toPx() + stroke / 2
            val outline = shape.createOutline(Size(size.width + 2 * out, size.height + 2 * out), layoutDirection, this)
            translate(-out, -out) { drawOutline(outline, Lfg.Accent, style = Stroke(stroke)) }
        }
    }
}

/**
 * A text field the pad can pass through. The pad stops on the field as on a
 * button, without opening the keyboard; A goes into it and opens the keyboard.
 * Inside, up and down go on to the next control (in a field of many lines,
 * only from the pad, so a keyboard's arrows still move in the text). Touch
 * goes straight in, as before.
 */
fun Modifier.padField(shape: Shape, singleLine: Boolean): Modifier = composed {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val input = LocalInputModeManager.current
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    focusRing(shape, orInside = true).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val native = event.nativeKeyEvent
        val pad = native.isFromSource(InputDevice.SOURCE_GAMEPAD) || native.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            native.isFromSource(InputDevice.SOURCE_DPAD)
        when (event.key) {
            Key.ButtonA, Key.DirectionCenter -> {
                if (!editing) {
                    editing = true
                    runCatching { field.requestFocus() }
                }
                keyboard?.show()
                true
            }
            Key.DirectionUp, Key.DirectionDown -> editing && (singleLine || pad) &&
                focus.moveFocus(if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
            else -> false
        }
    }
        .focusable()
        // The field itself, inside: reached by A from the pad, or by touch.
        .onFocusChanged { if (!it.hasFocus) editing = false }
        .focusProperties { canFocus = editing || input.inputMode == InputMode.Touch }
        .focusRequester(field)
}

/**
 * Up and down on the pad for a page: focus goes to the next control that way,
 * and where there is none (a page of text, the end of a list of notes) the
 * page scrolls instead, so everything on it can be read without touch. Once
 * it has scrolled past the focused control, it keeps scrolling that way, and
 * the other way scrolls back to the control before focus moves again: from a
 * control scrolled out of sight, the nearest control "below" could be the
 * page's own header.
 */
fun Modifier.padScroll(scroll: ScrollableState): Modifier = composed {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(0) }
    // How far the page has scrolled beyond the focused control: down is positive.
    var past by remember { mutableFloatStateOf(0f) }
    onSizeChanged { height = it.height }.onFocusChanged { past = 0f }.onKeyEvent { event ->
        val down = when (event.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return@onKeyEvent false
        }
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val page = (if (down) 0.6f else -0.6f) * height
        val back = past != 0f && (past > 0) != down
        // At once rather than animated: the next press can come before an animation ends, and must
        // know how far the page went. An animation cut short by the next one never says.
        fun scrollBy(by: Float) {
            past += by
            scope.launch {
                past += scroll.scrollBy(by) - by
                if (kotlin.math.abs(past) < 1f) past = 0f
            }
        }
        when {
            // Back towards the focused control, no farther than where it was.
            back -> scrollBy(if (down) minOf(page, -past) else maxOf(page, -past))
            past == 0f && focus.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up) -> past = 0f
            else -> scrollBy(page)
        }
        true
    }
}

/**
 * Where the pad's focus starts on a page opened with the pad: the first
 * control inside this group. Opened by touch, nothing is focused.
 */
fun Modifier.firstFocus(): Modifier = composed {
    val first = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        if (input.inputMode == InputMode.Keyboard) runCatching { first.requestFocus() }
    }
    focusRequester(first).focusGroup()
}

@Composable
fun LfgButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusRing(RoundedCornerShape(5.dp)).defaultMinSize(minHeight = 44.dp),
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, if (!enabled) Lfg.Soft else if (primary) Lfg.Fill else Lfg.BorderStrong),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Lfg.Fill else Lfg.Surface,
            contentColor = if (primary) Lfg.OnFill else Lfg.Text,
            disabledContainerColor = Lfg.Soft,
            disabledContentColor = Lfg.Dim,
        ),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
