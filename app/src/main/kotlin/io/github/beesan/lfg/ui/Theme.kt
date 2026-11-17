// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's palette: Hachidori's default theme, as the plan draws every screen. */
object Lfg {
    val Bg = Color(0xFF22232C)
    val Surface = Color(0xFF2B2B36)
    val Sunken = Color(0xFF1D1E26)
    val Border = Color(0xFF454653)
    val BorderStrong = Color(0xFF747586)
    val Text = Color(0xFFE8E5EB)
    val Dim = Color(0xFFAAA8B2)
    val Accent = Color(0xFFB1A5EE)
    val Fill = Color(0xFFA89CE4)
    val Soft = Color(0xFF353247)
    val OnFill = Color(0xFF211D30)
    val Wash = Color(0x3DB1A5EE)
    val Ok = Color(0xFF8BB79B)
    val Warn = Color(0xFFD1AA71)
    val Error = Color(0xFFD28A8C)
}

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
