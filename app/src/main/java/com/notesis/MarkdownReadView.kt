package com.notesis

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

/*
 * A Markdown note read on screen (md-read.html): the bundled marked, KaTeX
 * and highlight.js lay it out as GitHub and Obsidian do, the same as its PDF.
 * Taps on its links and tasks come back here through a small bridge.
 */

private const val READ_ORIGIN = "https://markdown.notesis.invalid"

/** What a request from the reading page asks for: one of the bundled files, or one of the note's own. */
internal sealed interface ReadResource {
    data class Asset(val path: String) : ReadResource
    data class NoteFile(val name: String) : ReadResource
}

/** Only the packaged viewer and the note's own files can be asked for; anything else, null. */
internal fun markdownReadResource(url: String): ReadResource? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.rawAuthority != "markdown.notesis.invalid") return null
    val path = uri.rawPath ?: return null
    if (path.startsWith("/viewer/file/")) {
        val name = URLDecoder.decode(path.removePrefix("/viewer/file/"), "UTF-8")
        if (name.isBlank() || name.split('/').any { it == ".." || it == "." } || name.startsWith("/")) return null
        return ReadResource.NoteFile(name)
    }
    if (!path.startsWith("/viewer/") || !path.matches(Regex("/[A-Za-z0-9_./-]+")) || path.contains("..")) return null
    ReadResource.Asset(path.removePrefix("/"))
}.getOrNull()

private fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "html" -> "text/html"
    "js" -> "text/javascript"
    "css" -> "text/css"
    "woff2" -> "font/woff2"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    "heic", "heif" -> "image/heif"
    else -> "application/octet-stream"
}

private fun Color.css(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

/**
 * [text] laid out by md-read.html. [compact] is for one block inside the
 * editor (a formula): no page round it, and [onHeight] hears how tall it
 * came out, in dp.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
internal fun MarkdownWebText(
    text: String,
    source: MarkdownSource?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onTask: (Int) -> Unit = {},
    onFollow: (String) -> Unit = {},
    onUrl: (String) -> Unit = {},
    onHeight: ((Int) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.4f || isSystemInDarkTheme() && scheme.surface.luminance() < 0.5f
    val options = remember(scheme, compact) {
        JSONObject()
            .put("bg", if (compact) "transparent" else scheme.surface.css())
            .put("fg", scheme.onSurface.css())
            .put("accent", scheme.primary.css())
            .put("muted", scheme.onSurfaceVariant.css())
            .put("line", scheme.outlineVariant.css())
            .put("code", scheme.surfaceVariant.css())
            .put("mark", if (dark) "#6b5a00" else "#fff3a3")
            .put("dark", dark)
            .put("compact", compact)
            .put("words", JSONObject()
                .put("copy", "Copy".tr).put("copied", "Copied".tr).put("notFound", "{0} (not found)".tr)
                .put("noText", "(no text)".tr).put("picture", "Picture".tr).put("empty", "Nothing written yet.".tr))
            .toString()
    }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val task by rememberUpdatedState(onTask)
    val follow by rememberUpdatedState(onFollow)
    val url by rememberUpdatedState(onUrl)
    val height by rememberUpdatedState(onHeight)
    var view by remember { mutableStateOf<WebView?>(null) }
    if (failed) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text("The reading view stopped. Switch to Live or Source to keep writing.")
        }
        return
    }
    LaunchedEffect(view, ready, text, options) {
        val web = view ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        web.evaluateJavascript("notesisRead.show(${JSONObject.quote(text)}, $options)", null)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(if (compact) android.graphics.Color.TRANSPARENT else scheme.surface.toArgb())
                isVerticalScrollBarEnabled = !compact
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportZoom(false)
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(object {
                    @JavascriptInterface fun ready() { main.post { ready = true } }
                    @JavascriptInterface fun toggleTask(index: Int) { main.post { task(index) } }
                    @JavascriptInterface fun follow(target: String) { main.post { follow(target) } }
                    @JavascriptInterface fun open(address: String) { main.post { url(address) } }
                    @JavascriptInterface fun size(cssPx: Int) { main.post { height?.invoke(cssPx) } }
                }, "NotesisBridge")
                webViewClient = object : WebViewClient() {
                    // The page never goes anywhere: links are sent to the app.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val address = request.url.toString()
                        // Pictures on the web load as they are; nothing else leaves the app.
                        if (!address.startsWith(READ_ORIGIN)) return if (request.url.scheme == "https" && request.method == "GET") null else denied()
                        val wanted = markdownReadResource(address) ?: return denied()
                        return try {
                            val (input, name) = when (wanted) {
                                is ReadResource.Asset -> context.assets.open(wanted.path) to wanted.path
                                is ReadResource.NoteFile -> (source?.openFile(wanted.name) ?: return notFound()) to wanted.name
                            }
                            WebResourceResponse(mimeOf(name), "UTF-8", 200, "OK",
                                mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), input)
                        } catch (_: Exception) { notFound() }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        failed = true
                        return true
                    }
                }
                loadUrl("$READ_ORIGIN/viewer/md-read.html")
                view = this
            }
        },
        onRelease = { web ->
            web.stopLoading()
            web.removeJavascriptInterface("NotesisBridge")
            web.destroy()
        },
    )
}

private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** A formula as KaTeX draws it, sized to itself; for a $$ block in the editor. */
@Composable
internal fun MathBlockView(tex: String, modifier: Modifier = Modifier) {
    var height by remember { mutableIntStateOf(56) }
    MarkdownWebText("$$\n$tex\n$$", null, modifier.fillMaxWidth().height(height.coerceIn(24, 2000).dp), compact = true,
        onHeight = { height = it + 8 })
}
