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
import io.github.beesan.lfg.dict.Entry
import io.github.beesan.lfg.dict.EntryHtml
import java.io.ByteArrayInputStream

/**
 * A WebView that shows one dictionary entry in full: pictures, tables and
 * each dictionary's own styling. It runs no script and loads nothing from the
 * network; pictures come from the dictionary's own files. The audio button
 * is a link the view catches and answers by playing the word.
 */
@SuppressLint("ViewConstructor")
class EntryWebView(context: Context, private val media: (dictionary: String, path: String) -> ByteArray?) : WebView(context) {
    private var shown: String? = null
    private var entry: Entry? = null
    private val audio = (context.applicationContext as? App)?.wordAudio

    init {
        setBackgroundColor(0xFF22232C.toInt())
        settings.javaScriptEnabled = false
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
                if (request.url.toString() == EntryHtml.AUDIO_URL) entry?.let { audio?.play(it) }
                return true
            }
        }
    }

    fun show(entry: Entry?, styles: List<EntryHtml.Style>, message: String? = null) {
        this.entry = entry
        val html = EntryHtml.page(entry, styles, message, audio = entry != null && audio?.has(entry) == true)
        if (html == shown) return
        shown = html
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
        update = { it.show(entry, styles, message) },
        modifier = modifier,
    )
}
