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
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import io.github.beesan.lfg.lookup.CardButton
import io.github.beesan.lfg.lookup.LookupState
import java.io.ByteArrayInputStream

/** What a button in the list of entries asks for, with the entry's place in the list. */
enum class EntryLink { CARD, AUDIO, NOTE }

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
                    EntryHtml.AUDIO_URL.substringBeforeLast('/') -> handler(EntryLink.AUDIO, index)
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
                pending?.let { evaluateJavascript(it, null) }
                pending = null
            }
        }
    }

    fun show(entry: Entry?, styles: List<EntryHtml.Style>, message: String?, palette: Palette) {
        this.entry = entry
        setBackgroundColor(palette.bg)
        val html = EntryHtml.page(entry, styles, message, audio = entry != null && audio?.has(entry) == true, palette = palette)
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
        notes: Boolean = true,
    ) {
        setBackgroundColor(palette.bg)
        val scroll = entries === listed && current != listedCurrent
        listed = entries
        listedCurrent = current
        if (entries.isEmpty()) {
            load(EntryHtml.page(null, styles, message, palette = palette))
            return
        }
        load(EntryHtml.list(entries, styles, palette, notes) { audio?.has(it) == true })
        val script = "lfgState([" + states.joinToString(",") { "'$it'" } + "],$current,$scroll)"
        if (loaded) evaluateJavascript(script, null) else pending = script
    }

    private fun load(html: String) {
        if (html == shown) return
        shown = html
        loaded = false
        pending = null
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

/** Every entry of a lookup, each with its own buttons: [EntryWebView.showList] in Compose. */
@Composable
fun EntryList(
    state: LookupState,
    styles: List<EntryHtml.Style>,
    media: (String, String) -> ByteArray?,
    onLink: (EntryLink, Int) -> Unit,
    modifier: Modifier = Modifier,
    notes: Boolean = true,
) {
    AndroidView(
        factory = { EntryWebView(it, media, onLink) },
        update = { it.showList(state.entries, styles, state.buttons.map { b -> b.htmlState() }, state.entryIndex, state.message, Lfg.palette, notes) },
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
