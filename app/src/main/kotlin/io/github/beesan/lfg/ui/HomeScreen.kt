// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.Prefs
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.Updates
import io.github.beesan.lfg.data.GameLine
import io.github.beesan.lfg.widget.WidgetData
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * What opens from the launcher: the game last read and its last line, today
 * against the goals, the last few lines, and the way to everything else. Side
 * by side on a wide screen; on the Thor's lower screen, one above the other.
 */
@Composable
fun HomeScreen(
    lines: List<GameLine>,
    stats: StatsState,
    prefs: Prefs,
    reading: Boolean,
    problem: Pair<String, String>?,
    onProblem: () -> Unit,
    onTurnOn: () -> Unit,
    onOpenLine: (GameLine) -> Unit,
    onLines: () -> Unit,
    onStats: () -> Unit,
    onGoals: () -> Unit,
    onDiary: () -> Unit,
    onRecap: () -> Unit,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    shortcut: String = "L3 + R3",
    loaded: Boolean = true,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = System.currentTimeMillis()
    val today = ReadingStats.dayOf(now / 1000.0, zone)
    val day = remember(stats, prefs.goals, prefs.goalCards, today) {
        WidgetData.of(stats.days, prefs.goals, today, stats.cards.map { ReadingStats.dayOf(it.madeAt / 1000.0, zone) }, prefs.goalCards)
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padScroll(scroll).verticalScroll(scroll).firstFocus(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeHeader(today, reading, onTurnOn, onSettings)
        if (problem != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(problem.first, color = Lfg.Warn, fontSize = 15.sp, modifier = Modifier.weight(1f))
                // No word for the button: there is nothing to press yet.
                if (problem.second.isNotEmpty()) LfgButton(problem.second, onProblem, primary = true)
            }
        }
        val latest = lines.firstOrNull()
        val continueCard = @Composable { ContinueCard(latest, lines, now, today, zone, shortcut, loaded, onOpenLine, onRecap, onLines) }
        val todayCard = @Composable { TodayCard(day, stats.loaded, onStats, onGoals) }
        val lastLines = @Composable { LastLines(lines.drop(1).take(4), onOpenLine, onLines) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= NARROW) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        continueCard()
                        if (lines.size > 1) lastLines()
                    }
                    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        todayCard()
                        Tiles(2, onLines, onStats, onDiary, onAsk)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    continueCard()
                    todayCard()
                    if (lines.size > 1) lastLines()
                    Tiles(4, onLines, onStats, onDiary, onAsk)
                }
            }
        }
    }
}

/**
 * What Home says of a newer LFG, and the word on its button: no word while it is being fetched
 * or installed. One that could not be fetched or installed is offered as it was before LFG
 * updated itself, for the browser to get.
 */
internal fun updateBanner(state: Updates.State): Pair<String, String>? = when (state) {
    Updates.State.None -> null
    is Updates.State.Available -> "LFG ${state.release.version} is out" to "Update"
    is Updates.State.Downloading -> "Getting LFG ${state.release.version}…" to ""
    is Updates.State.Ready -> "LFG ${state.release.version} is ready" to "Install"
    is Updates.State.Installing -> "Installing LFG ${state.release.version}…" to ""
    is Updates.State.Failed -> "LFG ${state.release.version} is out" to "Update"
}

@Composable
private fun HomeHeader(today: LocalDate, reading: Boolean, onTurnOn: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LFG", color = Lfg.Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            remember(today) { today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())) },
            color = Lfg.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        val chip = Modifier.heightIn(min = 36.dp).focusRing(RoundedCornerShape(18.dp))
        if (reading) {
            Row(chip.background(Lfg.OkSoft, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Lfg.Ok, CircleShape))
                Text("Reading", color = Lfg.Ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            // Off, the chip is the way to turn it on.
            Row(
                chip.border(1.dp, Lfg.Warn, RoundedCornerShape(18.dp)).clickable(onClick = onTurnOn).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reading is off", color = Lfg.Warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        LfgButton("Settings", onSettings)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, strong: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().background(Lfg.Surface, shape).border(if (strong) 2.dp else 1.dp, if (strong) Lfg.Accent else Lfg.Border, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Label(text: String) =
    Text(text.uppercase(), color = Lfg.Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ContinueCard(
    latest: GameLine?,
    lines: List<GameLine>,
    now: Long,
    today: LocalDate,
    zone: ZoneId,
    shortcut: String,
    loaded: Boolean,
    onOpenLine: (GameLine) -> Unit,
    onRecap: () -> Unit,
    onLines: () -> Unit,
) = Card(strong = latest != null) {
    if (latest == null) {
        Label("Start")
        // Before the store has answered, an empty list says nothing yet.
        if (loaded) Text("No lines yet. Open a game. Tap a word, or press $shortcut.", color = Lfg.Text, fontSize = 17.sp, lineHeight = 26.sp)
        return@Card
    }
    val gameToday = lines.count { it.gameId == latest.gameId && ReadingStats.dayOf(it.timestamp, zone) == today }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label("Continue")
            Text(latest.gameName, color = Lfg.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    "Last read " + ago(now, latest.createdAt, today, zone),
                    "$gameToday ${if (gameToday == 1) "line" else "lines"} today".takeIf { gameToday > 0 },
                ).joinToString(" · "),
                color = Lfg.Dim, fontSize = 13.sp,
            )
        }
    }
    Text(
        latest.lineText, color = Lfg.Text, fontSize = 20.sp, lineHeight = 32.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(6.dp), inside = true).clickable { onOpenLine(latest) }.padding(vertical = 2.dp),
    )
    latest.translation?.let { Text(it, color = Lfg.Dim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LfgButton("Look up", { onOpenLine(latest) }, primary = true)
        LfgButton("Recap", onRecap)
        LfgButton("Lines", onLines)
    }
}

/** "just now", "12 min ago", "3 h ago"; before today, the day. */
private fun ago(now: Long, then: Long, today: LocalDate, zone: ZoneId): String {
    val minutes = (now - then) / 60_000
    val day = ReadingStats.dayOf(then / 1000.0, zone)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        day == today -> "${minutes / 60} h ago"
        else -> MainViewModel.dayLabel(day, today).replaceFirstChar { it.lowercase() }
    }
}

@Composable
private fun TodayCard(day: WidgetData, loaded: Boolean, onStats: () -> Unit, onGoals: () -> Unit) = Card(
    Modifier.focusRing(RoundedCornerShape(16.dp)).clickable(onClick = onStats),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Today", color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (day.streak > 0) Text("${day.streak}-day streak", color = Lfg.Warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    if (!loaded) return@Card
    val p = Lfg.palette
    val goals = day.goals
    val rings = listOfNotNull(
        Triple(day.today.seconds / 60 / goals.minutes, Color(p.ringTime), WidgetData.timeParts(day.today.seconds).joinToString(" ") { it.first + " " + it.second } to "of ${WidgetData.goalTime(goals.minutes)}")
            .takeIf { goals.minutes > 0 },
        Triple(day.today.chars.toDouble() / goals.chars, Color(p.ringChars), short(day.today.chars) to "of ${short(goals.chars)} characters").takeIf { goals.chars > 0 },
        Triple(day.cards.toDouble() / day.cardGoal, Color(p.ringCards), "${day.cards}" to "of ${day.cardGoal} cards").takeIf { day.cardGoal > 0 },
    )
    if (rings.isEmpty()) {
        Text(
            "${WidgetData.timeParts(day.today.seconds).joinToString(" ") { it.first + " " + it.second }} · ${short(day.today.chars)} characters · ${day.cards} cards",
            color = Lfg.Text, fontSize = 15.sp,
        )
        LfgButton("Set goals", onGoals)
        return@Card
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        for ((share, colour, words) in rings) Ring(share.toFloat(), colour, words.first, words.second)
    }
}

/** 6,180 as "6.2k", 5,000 as "5k"; under a thousand as it is. */
private fun short(n: Int): String = when {
    n < 1000 -> "$n"
    n < 10_000 && n % 1000 >= 50 -> String.format(Locale.getDefault(), "%.1fk", n / 1000.0)
    else -> "${n / 1000}k"
}

@Composable
private fun Ring(share: Float, colour: Color, value: String, caption: String, size: Dp = 72.dp) {
    val track = Lfg.Border
    Column(
        Modifier.width(88.dp).semantics(mergeDescendants = true) { contentDescription = "$value $caption" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arc = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                val sweep = 360f * share.coerceIn(0f, 1f)
                if (sweep > 0f) drawArc(colour, -90f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(value, color = Lfg.Text, fontSize = if (value.length > 5) 12.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(caption, color = Lfg.Dim, fontSize = 12.sp, maxLines = 2, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun LastLines(lines: List<GameLine>, onOpenLine: (GameLine) -> Unit, onLines: () -> Unit) = Card {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Last lines", color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(
            "All lines", color = Lfg.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.focusRing(RoundedCornerShape(6.dp)).clickable(onClick = onLines).padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
    Column {
        for (line in lines) {
            HorizontalDivider(color = Lfg.Border)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).focusRing(RoundedCornerShape(6.dp), inside = true).clickable { onOpenLine(line) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(time.format(Date(line.createdAt)), color = Lfg.Dim, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                Text(line.lineText, color = Lfg.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (line.noteIds.isNotEmpty()) Text("Card", color = Lfg.Ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun Tiles(columns: Int, onLines: () -> Unit, onStats: () -> Unit, onDiary: () -> Unit, onAsk: () -> Unit) {
    val tiles = listOf("Lines" to onLines, "Stats" to onStats, "Diary" to onDiary, "Ask" to onAsk)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((name, open) in row) {
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).heightIn(min = 56.dp).focusRing(shape).background(Lfg.Surface, shape).border(1.dp, Lfg.Border, shape)
                            .clickable(onClick = open).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(name, color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
