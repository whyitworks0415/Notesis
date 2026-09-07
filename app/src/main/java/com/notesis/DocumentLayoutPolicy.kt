package com.notesis

import java.net.URI
import java.util.Locale

internal const val DOCUMENT_ORIGIN = "https://document.notesis.invalid"

internal fun supportsDocumentLayout(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
        "hwp", "hwpx", "ppt", "pptx", "md", "markdown", "txt", "xls", "xlsx",
    )

/** Only packaged renderer assets and the one selected document are addressable. */
internal fun documentResourcePath(url: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.rawAuthority != "document.notesis.invalid") return null
    val path = uri.rawPath ?: return null
    if (path == "/document") return "document"
    if (!path.startsWith("/viewer/") || !path.matches(Regex("/[A-Za-z0-9_./-]+")) ||
        path.contains("..")) return null
    path.removePrefix("/")
}.getOrNull()
