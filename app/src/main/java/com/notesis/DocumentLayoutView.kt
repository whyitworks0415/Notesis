package com.notesis

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.Locale

/** Offline renderer. No JavaScript bridge, file access, network access or source mutation. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DocumentLayoutView(
    name: String,
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    /** Laying the document out for a PDF: a tap on a paragraph starts it on a new page (see notesis-print.js, md-print.js). */
    arrange: Boolean = false,
    /** The view while it is up, then null: the PDF is printed from it. */
    onWebView: ((WebView?) -> Unit)? = null,
) {
    var processFailed by remember(bytes) { mutableStateOf(false) }
    if (processFailed) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Not enough memory to show the document, or the viewer stopped. Open it again, or use the text view at the top.")
        }
        return
    }
    key(bytes, arrange) {
      AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // A WRAP_CONTENT WebView reports a zero CSS viewport height, even
                // when Compose has measured its native view to fill the screen.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.rgb(232, 236, 241))
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val path = documentResourcePath(request.url.toString())
                        if (request.method != "GET" || path == null) return denied()
                        return try {
                            val input = if (path == "document") ByteArrayInputStream(bytes)
                            else context.assets.open(path)
                            val mime = when (path.substringAfterLast('.')) {
                                "html" -> "text/html"
                                "js" -> "text/javascript"
                                "css" -> "text/css"
                                "wasm" -> "application/wasm"
                                "woff2" -> "font/woff2"
                                "txt" -> "text/plain"
                                else -> "application/octet-stream"
                            }
                            WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                                "Cache-Control" to "no-store",
                                "X-Content-Type-Options" to "nosniff",
                                "Content-Security-Policy" to DOCUMENT_CSP,
                            ), input)
                        } catch (_: Exception) { denied() }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        processFailed = true
                        return true
                    }
                }
                val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
                // Markdown is laid out for a PDF on real pages, styled as GitHub shows it (see md-print.js).
                if (arrange && extension in setOf("md", "markdown")) loadUrl("$DOCUMENT_ORIGIN/viewer/md-print.html?arrange=1")
                else loadUrl("$DOCUMENT_ORIGIN/viewer/index.html?format=$extension" + if (arrange) "&arrange=1" else "")
                onWebView?.invoke(this)
            }
        },
        onRelease = { view ->
            onWebView?.invoke(null)
            view.stopLoading()
            view.removeAllViews()
            view.destroy()
        },
      )
    }
}

private fun denied() = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
)

private const val DOCUMENT_CSP = "default-src 'none'; " +
    "script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
    "img-src data: blob:; font-src 'self' data: blob:; connect-src 'self'; " +
    "base-uri 'none'; form-action 'none'; frame-src 'none'; object-src 'none'"
