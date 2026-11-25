// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.beesan.lfg.App
import io.github.beesan.lfg.core.Palette
import io.github.beesan.lfg.core.Perf
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.CardButton
import io.github.beesan.lfg.lookup.LookupState
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream

/**
 * What a button in the list of entries asks for, with the entry's place in the list;
 * for [KANJI], the tapped kanji's code point instead. [BACK] goes from a kanji's entry back to the word's.
 */
enum class EntryLink { CARD, AUDIO, NOTE, VN_YES, VN_NO, KANJI, BACK }

/** A card button's state as the entry page's script names it. */
fun CardButton.htmlState() = when (this) {
    CardButton.ADD -> "add"
    CardButton.ADDING -> "adding"
    CardButton.IN_ANKI -> "in-anki"
    CardButton.WAITING -> "waiting"
}

/**
 * A WebView that shows dictionary entries in full: pictures, tables and
 * each dictionary's own styling. It loads nothing from the network; pictures
 * come from the dictionary's own files. The audio button is a link the view
 * catches and answers by playing the word.
 *
 * With [onLink] it shows every entry of a lookup ([showList]): over a game,
 * on the second screen, or for a stored line. Each button's link goes to
 * [onLink]. Only then does it run script, the page's own `lfgState`, which
 * sets the buttons without a reload.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class EntryWebView(
    context: Context,
    private val media: (dictionary: String, path: String) -> ByteArray?,
    private val onLink: ((EntryLink, Int) -> Unit)? = null,
) : WebView(context) {
    /**
     * The popup over a game: the page is clear, so the popup's own see-through
     * ground shows behind the entries. Set before the first page is shown.
     */
    var glass = false
    private var shown: String? = null
    private var entry: Entry? = null
    private val audio = (context.applicationContext as? App)?.wordAudio
    private var loaded = false
    private var pending: String? = null
    private var listed: List<Entry>? = null
    private var listedCurrent = 0

    init {
        settings.javaScriptEnabled = onLink != null
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.scheme != EntryHtml.MEDIA_SCHEME) return null
                val segments = url.pathSegments
                val bytes = (if (segments.size >= 2) media(segments[0], segments.drop(1).joinToString("/")) else null)
                    ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return WebResourceResponse(mime(url), null, ByteArrayInputStream(bytes))
            }

            // Links in a dictionary point at other entries; nothing is ever navigated to.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val handler = onLink
                if (handler == null) {
                    if (url.toString() == EntryHtml.AUDIO_URL) entry?.let { audio?.play(it) }
                    return true
                }
                val index = url.lastPathSegment?.toIntOrNull() ?: return true
                when ("${url.scheme}://${url.host}") {
                    EntryHtml.CARD_URL -> handler(EntryLink.CARD, index)
                    EntryHtml.NOTE_URL -> handler(EntryLink.NOTE, index)
                    EntryHtml.VN_YES_URL -> handler(EntryLink.VN_YES, index)
                    EntryHtml.VN_NO_URL -> handler(EntryLink.VN_NO, index)
                    EntryHtml.KANJI_URL -> handler(EntryLink.KANJI, index)
                    EntryHtml.BACK_URL -> handler(EntryLink.BACK, index)
                    EntryHtml.AUDIO_URL.substringBeforeLast('/') -> handler(EntryLink.AUDIO, index)
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!loaded && loadStarted != 0L) Perf.add("entry.load", System.nanoTime() - loadStarted)
                loaded = true
                pending?.let { run(it) }
                pending = null
            }
        }
    }

    fun show(entry: Entry?, styles: List<EntryHtml.Style>, message: String?, palette: Palette) {
        this.entry = entry
        listHead = null
        ground(palette)
        val html = EntryHtml.page(entry, styles, message, audio = entry != null && audio?.has(entry) == true, palette = palette, glass = glass)
        load(html)
    }

    /**
     * Every entry of a lookup with each one's card button in [states]
     * (`add`, `adding`, `in-anki`, `waiting`) and [current] marked as the
     * controller's. Only a new lookup reloads the page, so the scroll stays
     * put when a button changes; when L1 or R1 moves the controller to
     * another entry, the list scrolls to it.
     */
    fun showList(
        entries: List<Entry>, styles: List<EntryHtml.Style>, states: List<String>, current: Int, message: String?, palette: Palette,
        notes: Boolean = true, askVn: String? = null, back: String? = null,
    ) {
        ground(palette)
        val scroll = entries === listed && current != listedCurrent
        listed = entries
        listedCurrent = current
        if (entries.isEmpty()) {
            listHead = null
            load(EntryHtml.page(null, styles, message, palette = palette, glass = glass))
            return
        }
        val head = Perf.measure("entry.head") { EntryHtml.listHead(styles, palette, glass) }
        val body = Perf.measure("entry.html") { EntryHtml.listBody(entries, notes, askVn, back) { audio?.has(it) == true } }
        val state = "lfgState([" + states.joinToString(",") { "'$it'" } + "],$current,$scroll)"
        val script = when {
            head != listHead -> {
                // New styles or colours: the whole page, once.
                listHead = head
                listBody = body
                pageBody = body
                load(head + body + EntryHtml.LIST_END)
                state
            }
            // The page is already there: only the entries change.
            body != listBody -> {
                listBody = body
                swapStarted = System.nanoTime()
                Perf.add("entry.kchars", body.length * 1000L)
                "lfgShow(" + JsonPrimitive(body) + ");" + state
            }
            else -> state
        }
        if (loaded) {
            run(script)
            pageBody = listBody
        } else {
            // Still loading: whatever comes next replaces what was waiting, so it must carry the
            // entries too, or a change of buttons alone would leave the page as it loaded.
            pending = if (listBody != pageBody) "lfgShow(" + JsonPrimitive(listBody) + ");" + state else state
        }
    }

    /**
     * Loads the list's page with no entries yet, so the first lookup only
     * swaps its entries in (about 30 ms) instead of starting the page from
     * nothing (200-570 ms on the emulator). Does nothing once a page is up.
     */
    fun prewarm(styles: List<EntryHtml.Style>, palette: Palette) {
        if (shown != null) return
        ground(palette)
        val head = Perf.measure("entry.head") { EntryHtml.listHead(styles, palette, glass) }
        listHead = head
        listBody = ""
        pageBody = ""
        load(head + EntryHtml.LIST_END)
    }

    /** Back to the top of the page: when the popup comes back from being parked, where its page was laid out one pixel wide. */
    fun toTop() {
        scrollTo(0, 0)
        if (loaded) evaluateJavascript("window.scrollTo(0,0)", null)
    }

    private fun ground(palette: Palette) = setBackgroundColor(if (glass) android.graphics.Color.TRANSPARENT else palette.bg)

    private var listHead: String? = null
    private var listBody: String? = null

    /** The entries the page holds, or will once it has loaded and run what is waiting. */
    private var pageBody: String? = null
    private var swapStarted = 0L

    private fun run(script: String) {
        val started = swapStarted
        swapStarted = 0L
        evaluateJavascript(script) { if (started != 0L) Perf.add("entry.swap", System.nanoTime() - started) }
    }

    private var loadStarted = 0L

    private fun load(html: String) {
        if (html == shown) return
        shown = html
        loaded = false
        pending = null
        loadStarted = System.nanoTime()
        Perf.add("entry.kchars", html.length * 1000L)  // shows as "ms": thousands of characters
        loadDataWithBaseURL("${EntryHtml.MEDIA_SCHEME}://entry/", html, "text/html", "utf-8", null)
    }

    private fun mime(url: Uri): String = when (url.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

/** Scrolls an [EntryList] from outside it: the pad's shoulder triggers, a page at a time. */
class EntryScroller {
    internal var view: EntryWebView? = null

    /** By [dy] pixels, as the controller's stick scrolls it. */
    fun scrollBy(dy: Int) {
        view?.scrollBy(0, dy)
    }

    /** A good part of a page down, or up. */
    fun page(down: Boolean) {
        val view = view ?: return
        view.scrollBy(0, (if (down) 0.7f else -0.7f).times(view.height).toInt())
    }
}

/** Every entry of a lookup, each with its own buttons: [EntryWebView.showList] in Compose. */
@Composable
fun EntryList(
    state: LookupState,
    styles: List<EntryHtml.Style>,
    media: (String, String) -> ByteArray?,
    onLink: (EntryLink, Int) -> Unit,
    modifier: Modifier = Modifier,
    notes: Boolean = true,
    scroller: EntryScroller? = null,
) {
    AndroidView(
        factory = { EntryWebView(it, media, onLink).also { view -> scroller?.view = view } },
        update = { it.showList(state.entries, styles, state.buttons.map { b -> b.htmlState() }, state.entryIndex, state.message, Lfg.palette, notes, state.vnQuestion?.title, state.kanjiFrom) },
        modifier = modifier,
    )
}

@Composable
fun EntryWeb(
    entry: Entry?,
    styles: List<EntryHtml.Style>,
    message: String?,
    media: (String, String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { EntryWebView(it, media) },
        update = { it.show(entry, styles, message, Lfg.palette) },
        modifier = modifier,
    )
}
