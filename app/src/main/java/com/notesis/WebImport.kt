package com.notesis

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

/** A web page reduced to its title and readable text. */
internal data class WebPage(val title: String, val text: String)

/**
 * Fetches [address] and keeps what a reader would: the title and the body as
 * plain text, scripts and styles dropped. Capped in size and time; null on any
 * failure. Runs on the caller's thread, so call it off the main one.
 */
internal fun fetchWebPage(address: String): WebPage? = runCatching {
    val url = URL(if (address.startsWith("http")) address else "https://$address")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Notesis")
    }
    try {
        require(connection.responseCode in 200..299)
        val bytes = connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < MAX_PAGE_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        val charset = connection.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "UTF-8"
        webPageFrom(String(bytes, runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8)), url.host)
    } finally {
        connection.disconnect()
    }
}.getOrNull()

/** The title and readable text of [html]; [fallbackTitle] when it has no title. */
internal fun webPageFrom(html: String, fallbackTitle: String): WebPage {
    val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.get(1)?.let { decode(it).trim() }?.takeIf { it.isNotEmpty() } ?: fallbackTitle
    val body = html
        .replace(Regex("<(script|style|noscript|svg)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
    val text = decode(body)
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    return WebPage(title, text)
}

private fun decode(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()

private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
