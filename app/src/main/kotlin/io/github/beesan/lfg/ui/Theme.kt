// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.Palette

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
    val Warn get() = Color(palette.warn)
    val Error get() = Color(palette.error)
}

/** The palette for these settings, with the system's light or dark setting for "Match system". */
fun Context.palette(prefs: Prefs): Palette = Palette.of(
    prefs.colours,
    prefs.theme,
    systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
)

@Composable
fun LfgButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
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
