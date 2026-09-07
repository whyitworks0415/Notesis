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
import androidx.compose.material3.Text
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
internal fun DocumentLayoutView(name: String, bytes: ByteArray, modifier: Modifier = Modifier) {
    var processFailed by remember(bytes) { mutableStateOf(false) }
    if (processFailed) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text("문서를 표시할 메모리가 부족하거나 뷰어가 종료되었습니다. 다시 열거나 상단의 텍스트 보기를 이용해 주세요.")
        }
        return
    }
    key(bytes) {
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
                loadUrl("$DOCUMENT_ORIGIN/viewer/index.html?format=$extension")
            }
        },
        onRelease = { view ->
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
