// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.beesan.lfg.core.GameStats
import io.github.beesan.lfg.core.ReadingStats
import io.github.beesan.lfg.core.StatsPeriod
import io.github.beesan.lfg.data.Game
import io.github.beesan.lfg.games.Covers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Seconds since 1970, now, again every half minute: whether a session is still going changes with it. */
@Composable
internal fun rememberNow(): Double {
    val now by produceState(System.currentTimeMillis() / 1000.0) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1000.0
        }
    }
    return now
}

private fun clock(seconds: Double, zone: ZoneId): String =
    Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** "Today", "Yesterday", "Sat 14 Nov", or with the year when it is not this one. */
private fun shortDay(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> {
        val locale = Locale.getDefault()
        val base = day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + day.dayOfMonth + " " + day.month.getDisplayName(TextStyle.SHORT, locale)
        if (day.year == today.year) base else "$base ${day.year}"
    }
}

private fun longDay(day: LocalDate): String =
    "${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${day.year}"

private fun speed(charsPerHour: Int) = if (charsPerHour == 0) "–" else "%,d".format(charsPerHour)

private fun appLabel(context: Context, packageName: String): String = try {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
} catch (_: Exception) {
    packageName
}

/** A Steam game's store header, or the icon of the app the game is read in, on a dim ground. */
@Composable
internal fun GameCover(game: Game?, width: Dp, height: Dp) {
    val context = LocalContext.current
    val iconPx = with(LocalDensity.current) { height.roundToPx() }
    val cover by produceState<Pair<ImageBitmap, Boolean>?>(null, game?.id) {
        value = game?.let { g ->
            withContext(Dispatchers.IO) {
                Covers.steamHeader(context, g)?.let { it.asImageBitmap() to true }
                    ?: Covers.appIcon(context, g, iconPx)?.let { it.asImageBitmap() to false }
            }
        }
    }
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(5.dp)).background(Lfg.Sunken).border(1.dp, Lfg.Border, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        cover?.let { (bitmap, header) ->
            if (header) {
                Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(bitmap, null, Modifier.fillMaxHeight().padding(4.dp), contentScale = ContentScale.Fit)
            }
        }
    }
}

/**
 * Today's sessions, one at a time, the newest first shown: its game, when it
 * started and ended, and what was read in it. With none today, the last one.
 */
@Composable
internal fun SessionBanner(stats: StatsState, games: Map<String, Game>, today: LocalDate, now: Double) {
    val zone = remember { ZoneId.systemDefault() }
    val list = remember(stats, today) { GameStats.recent(stats.summary.sessions, today, zone) }
    if (list.isEmpty()) return
    val cards = remember(stats) { GameStats.sessionCards(stats.summary.sessions, stats.cards) }
    var index by remember(list.size) { mutableIntStateOf(list.lastIndex) }
    val s = list[index.coerceIn(0, list.lastIndex)]
    val live = GameStats.live(s, now)
    val isToday = ReadingStats.dayOf(s.start, zone) == today
    val where = buildList {
        if (live) add("Started ${clock(s.start, zone)} · still going") else add("${clock(s.start, zone)} – ${clock(s.end, zone)}")
        if (!isToday) add(shortDay(ReadingStats.dayOf(s.start, zone), today))
        if (isToday && list.size > 1) add("session ${index + 1} of ${list.size} today")
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Lfg.Soft).border(1.dp, Lfg.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (live) Lfg.Ok else Lfg.Dim))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                (if (live) "This session · " else "Session · ") + (games[s.gameId]?.title ?: "Unknown game"),
                color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(where, color = Lfg.Dim, fontSize = 13.sp, maxLines = 1)
        }
        Figure(duration(s.seconds), "")
        Figure("%,d".format(s.chars), "characters")
        Figure(speed(s.charsPerHour), "an hour")
        Figure("${cards[s] ?: 0}", if (cards[s] == 1) "card" else "cards")
        if (list.size > 1) {
            LfgButton("‹", { index = (index - 1).coerceAtLeast(0) }, enabled = index > 0)
            LfgButton("›", { index = (index + 1).coerceAtMost(list.lastIndex) }, enabled = index < list.lastIndex)
        }
    }
}

@Composable
private fun Figure(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (unit.isNotEmpty()) Text(" $unit", color = Lfg.Dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp))
    }
}

private val COVER_W = 64.dp
private val COVER_H = 30.dp

/** Each game read in [period], with its share of the reading as a bar; tap one for its own page. */
@Composable
internal fun GamesList(stats: StatsState, games: Map<String, Game>, period: StatsPeriod, today: LocalDate, onGame: (String) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(GameStats.Sort.TIME) }
    val zone = remember { ZoneId.systemDefault() }
    val rows = remember(stats, period, sort, today) { GameStats.rows(stats.summary, stats.cards, period, today, zone, sort) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
        Text("Games", color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (rows.size == 1) "1 game" else "${rows.size} games", color = Lfg.Dim, fontSize = 14.sp, maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Choice(listOf(GameStats.Sort.TIME.name to "Time", GameStats.Sort.RECENT.name to "Recent"), sort.name) { sort = GameStats.Sort.valueOf(it) }
    }
    if (rows.isEmpty()) {
        Text("Nothing read in this period.", color = Lfg.Dim, fontSize = 15.sp)
        return
    }
    val most = rows.maxOf { it.totals.seconds }.coerceAtLeast(1.0)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < NARROW) NarrowGames(rows, games, most, today, zone, onGame) else WideGames(rows, games, most, today, zone, onGame)
    }
}

/**
 * The table on a narrow screen: five number columns left no room for the
 * name, which went one letter a line. Time and the day stay as columns; the
 * rest goes in a line under the name.
 */
@Composable
private fun NarrowGames(rows: List<GameStats.Row>, games: Map<String, Game>, most: Double, today: LocalDate, zone: ZoneId, onGame: (String) -> Unit) {
    Column {
        HorizontalDivider(color = Lfg.Border)
        rows.forEach { row ->
            val game = games[row.gameId]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRing(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)).clickable { onGame(row.gameId) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameCover(game, COVER_W, COVER_H)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(game?.title ?: "Unknown game", color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "%,d chars · %s/h · %s".format(row.totals.chars, speed(row.totals.charsPerHour), if (row.cards == 1) "1 card" else "${row.cards} cards"),
                        color = Lfg.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Lfg.Sunken)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((row.totals.seconds / most).toFloat().coerceIn(0.01f, 1f)).background(Lfg.Accent))
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(duration(row.totals.seconds), color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(shortDay(ReadingStats.dayOf(row.last, zone), today), color = Lfg.Dim, fontSize = 13.sp, maxLines = 1)
                }
            }
            HorizontalDivider(color = Lfg.Border.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun WideGames(rows: List<GameStats.Row>, games: Map<String, Game>, most: Double, today: LocalDate, zone: ZoneId, onGame: (String) -> Unit) = Column {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.width(COVER_W))
        Text("Game", color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Heading("Time", 88.dp)
        Heading("Characters", 96.dp)
        Heading("Speed", 84.dp)
        Heading("Cards", 52.dp)
        Heading("Last played", 96.dp)
    }
    HorizontalDivider(color = Lfg.Border)
    rows.forEach { row ->
        val game = games[row.gameId]
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRing(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)).clickable { onGame(row.gameId) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GameCover(game, COVER_W, COVER_H)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(game?.title ?: "Unknown game", color = Lfg.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Lfg.Sunken)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((row.totals.seconds / most).toFloat().coerceIn(0.01f, 1f)).background(Lfg.Accent))
                }
            }
            Cell(duration(row.totals.seconds), 88.dp, bold = true)
            Cell("%,d".format(row.totals.chars), 96.dp)
            Cell(speed(row.totals.charsPerHour), 84.dp)
            Cell("${row.cards}", 52.dp)
            Cell(shortDay(ReadingStats.dayOf(row.last, zone), today), 96.dp, dim = true)
        }
        HorizontalDivider(color = Lfg.Border.copy(alpha = 0.5f))
    }
}

@Composable
private fun Heading(text: String, width: Dp) =
    Text(text, color = Lfg.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width))

@Composable
private fun Cell(text: String, width: Dp, bold: Boolean = false, dim: Boolean = false) = Text(
    text, color = if (dim) Lfg.Dim else Lfg.Text, fontSize = if (dim) 14.sp else 16.sp,
    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width),
)

/**
 * One game: its time, characters and speed over a period as the main stats
 * have them, its sessions and cards, a bar a day or month, and its sessions.
 */
@Composable
fun GameStatsScreen(
    stats: StatsState, game: Game?, gameId: String, startPeriod: StatsPeriod, onBack: () -> Unit,
    /** The game under a new name, which its lines take too. */
    onRename: (Game) -> Unit = {},
) {
    // The name as typed, while the game is being renamed.
    var typed by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(startPeriod) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    var picked by remember(period) { mutableIntStateOf(-1) }
    val context = LocalContext.current
    Page(game?.title ?: "Game", onBack, actions = {
        Choice(StatsPeriod.entries.map { it.name to it.label }, period.name) { period = StatsPeriod.valueOf(it) }
    }) {
        if (!stats.loaded) return@Page
        val zone = remember { ZoneId.systemDefault() }
        val today = remember { ReadingStats.dayOf(System.currentTimeMillis() / 1000.0, zone) }
        val days = stats.summary.gameDays[gameId].orEmpty()
        val view = remember(stats, period) { period.view(days, today) }
        val all = remember(stats, period) { period.view(stats.days, today).totals }
        val sessions = remember(stats) { stats.summary.sessions.filter { it.gameId == gameId } }
        val sessionCards = remember(stats) { GameStats.sessionCards(sessions, stats.cards) }
        val shown = sessions.filter { ReadingStats.dayOf(it.start, zone) >= view.start }
        val cards = remember(stats, view) {
            stats.cards.count { it.gameId == gameId && ReadingStats.dayOf(it.madeAt / 1000.0, zone) in view.start..today }
        }
        val t = view.totals

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            GameCover(game, 184.dp, 86.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (game != null) {
                    val name = typed
                    if (name == null) {
                        LfgButton("Rename", { typed = game.title })
                    } else {
                        val save = {
                            name.trim().takeIf { it.isNotEmpty() && it != game.title }?.let { onRename(game.copy(title = it)) }
                            typed = null
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NameField(name, { typed = it }, game.title, save)
                            LfgButton("Save", save, primary = true)
                        }
                    }
                    val host = remember(game.packageName) { appLabel(context, game.packageName) }
                    Text(
                        if (game.namedBy.isEmpty()) host else "In $host, named by ${game.namedBy}",
                        color = Lfg.Dim, fontSize = 14.sp, maxLines = 1,
                    )
                }
                if (sessions.isNotEmpty()) {
                    Text(
                        "First played ${longDay(ReadingStats.dayOf(sessions.first().start, zone))} · " +
                            "Last played ${longDay(ReadingStats.dayOf(sessions.maxOf { it.end }, zone))}",
                        color = Lfg.Dim, fontSize = 14.sp, maxLines = 1,
                    )
                }
                Text(view.range, color = Lfg.Dim, fontSize = 14.sp, maxLines = 1)
            }
        }
        HorizontalDivider(color = Lfg.Border)
        if (t.lines == 0) {
            Text("Not played in this period.", color = Lfg.Dim, fontSize = 15.sp)
            return@Page
        }
        Row(Modifier.fillMaxWidth()) {
            Tile(
                "Reading time", durationParts(t.seconds),
                if (all.seconds > 0) "${(100 * t.seconds / all.seconds).toInt()}% of your reading" else "",
            )
            Tile("Characters", listOf("%,d".format(t.chars) to ""), if (t.lines == 1) "1 line" else "%,d lines".format(t.lines))
            Tile("Speed", listOf(speed(t.charsPerHour) to "an hour"), if (all.charsPerHour > 0) "Your average is " + "%,d".format(all.charsPerHour) else "")
            Tile(
                "Sessions", listOf("${shown.size}" to ""),
                if (shown.isNotEmpty()) duration(shown.sumOf { it.seconds } / shown.size) + " on average" else "",
            )
            Tile("Cards made", listOf("$cards" to ""), if (cards > 0) "One every " + "%,d".format(t.chars / cards) + " characters" else "")
        }
        HorizontalDivider(color = Lfg.Border)

        val value = { b: StatsPeriod.Bar -> if (showTime) b.seconds / 3600 else b.chars.toDouble() }
        val amount = { b: StatsPeriod.Bar -> if (showTime) duration(b.seconds) else "%,d characters".format(b.chars) }
        val top = view.bars.maxBy(value)
        val readout = when {
            picked in view.bars.indices -> view.bars[picked].let { b -> if (b.chars == 0) "${b.name} · Not played" else "${b.name} · ${amount(b)}" }
            else -> "Played on ${view.daysRead} of ${view.dayCount} ${if (view.dayCount == 1) "day" else "days"} · Most: ${amount(top)}, ${top.name}"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            Text(if (period.byDay) "Each day" else "Each month", color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(readout, color = Lfg.Dim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
            Choice(listOf("chars" to "Characters", "time" to "Time"), if (showTime) "time" else "chars") { showTime = it == "time" }
        }
        BarChart(view.bars.map(value), view.bars.map { it.axis }, showTime, null, picked, view.bars.map { "${it.name} · ${amount(it)}" }) {
            picked = if (picked == it) -1 else it
        }
        HorizontalDivider(color = Lfg.Border, modifier = Modifier.padding(top = 12.dp))

        Text("Sessions", color = Lfg.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        shown.asReversed().take(SESSIONS_SHOWN).forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Lfg.Surface).border(1.dp, Lfg.Border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    shortDay(ReadingStats.dayOf(s.start, zone), today) + ", " + clock(s.start, zone) + " – " + clock(s.end, zone),
                    color = Lfg.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1,
                )
                SessionCell(duration(s.seconds), 88.dp)
                SessionCell("%,d characters".format(s.chars), 150.dp)
                SessionCell(speed(s.charsPerHour) + " an hour", 130.dp)
                SessionCell((sessionCards[s] ?: 0).let { if (it == 1) "1 card" else "$it cards" }, 80.dp)
            }
        }
        if (shown.size > SESSIONS_SHOWN) {
            Text("and ${shown.size - SESSIONS_SHOWN} before", color = Lfg.Dim, fontSize = 13.sp)
        }
    }
}

private const val SESSIONS_SHOWN = 10

@Composable
private fun RowScope.SessionCell(text: String, width: Dp) =
    Text(text, color = Lfg.Dim, fontSize = 14.sp, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width))
