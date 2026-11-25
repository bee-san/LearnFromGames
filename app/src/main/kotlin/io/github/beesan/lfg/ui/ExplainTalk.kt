// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.ask.Explainer

/**
 * What was asked about a line after its explanation, as the lines' screen
 * over a game and the app's own line page both show it.
 */
@Immutable
data class TalkUi(
    val turns: List<Explainer.Turn> = emptyList(),
    /** The question with no answer yet: being answered, or with [error] not answered. */
    val asking: String? = null,
    val error: String? = null,
    /** Questions offered to ask next. */
    val suggestions: List<String> = emptyList(),
    /** No more can be asked of this line. */
    val full: Boolean = false,
)

/** Each question to the right in its bubble, its answer under it; last, the question still waiting for its answer. */
@Composable
fun TalkTurns(talk: TalkUi, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (turn in talk.turns) {
            Question(turn.question)
            Text(turn.answer, color = Lfg.Text, fontSize = 15.sp, lineHeight = 22.sp)
        }
        talk.asking?.let {
            Question(it)
            if (talk.error != null) Text(talk.error, color = Lfg.Warn, fontSize = 14.sp, lineHeight = 20.sp)
            else Text("Asking…", color = Lfg.Dim, fontSize = 15.sp)
        }
        if (talk.full) {
            Text(
                "That is ${Explainer.MAX_QUESTIONS} questions on this line. Explain again starts over.",
                color = Lfg.Dim, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun Question(text: String) {
    val shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 2.dp, bottomStart = 10.dp)
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            text, color = Lfg.Text, fontSize = 14.5.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(start = 48.dp).clip(shape).background(Lfg.Soft).padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** The questions offered next, in a row that scrolls sideways; a tap asks one. */
@Composable
fun TalkChips(suggestions: List<String>, enabled: Boolean, onAsk: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    if (suggestions.isNotEmpty()) Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (question in suggestions) {
            Text(
                question, color = if (enabled) Lfg.Text else Lfg.Dim, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp).clip(shape).background(Lfg.Surface).border(1.dp, Lfg.BorderStrong, shape)
                    .focusRing(shape).clickable(enabled = enabled) { onAsk(question) }
                    .defaultMinSize(minHeight = 38.dp).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
