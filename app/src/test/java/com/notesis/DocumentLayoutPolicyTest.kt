package com.notesis

import org.junit.Assert.*
import org.junit.Test

class DocumentLayoutPolicyTest {
    @Test fun requestedFormatsUseLayoutRatherThanTextExtraction() {
        for (extension in listOf("hwp", "hwpx", "ppt", "pptx", "md", "markdown", "txt", "xls", "xlsx")) {
            assertTrue(supportsDocumentLayout("문서.$extension"))
            assertTrue(supportsDocumentLayout("문서.${extension.uppercase()}"))
        }
        assertFalse(supportsDocumentLayout("archive.zip"))
        assertFalse(supportsDocumentLayout("문서.hwp.exe"))
    }

    @Test fun onlyLocalAssetsAndSelectedSourceAreReadable() {
        assertEquals("document", documentResourcePath("$DOCUMENT_ORIGIN/document"))
        assertEquals("viewer/viewer.js", documentResourcePath("$DOCUMENT_ORIGIN/viewer/viewer.js"))
        assertEquals("viewer/rhwp_bg.wasm", documentResourcePath("$DOCUMENT_ORIGIN/viewer/rhwp_bg.wasm"))
        for (url in listOf(
            "https://example.com/viewer/index.html",
            "http://document.notesis.invalid/document",
            "$DOCUMENT_ORIGIN.evil.test/document",
            "https://user@document.notesis.invalid/document",
            "$DOCUMENT_ORIGIN:8443/document",
            "$DOCUMENT_ORIGIN/viewer/../secrets.txt",
            "$DOCUMENT_ORIGIN/viewer/%2e%2e/secrets.txt",
            "$DOCUMENT_ORIGIN/viewer/%2Fdocument",
            "$DOCUMENT_ORIGIN/private.txt",
            "file:///sdcard/document.hwp",
            "content://com.notesis.files/private",
            "not a URL",
        )) assertNull(url, documentResourcePath(url))
    }
}
