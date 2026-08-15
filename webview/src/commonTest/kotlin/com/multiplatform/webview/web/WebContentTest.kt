package com.multiplatform.webview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WebContentTest {
    @Test
    fun postUsesByteContentForEqualityAndHashCode() {
        val first = WebContent.Post("https://example.test/post", byteArrayOf(1, 2, 3))
        val sameContent = WebContent.Post("https://example.test/post", byteArrayOf(1, 2, 3))
        val differentContent = WebContent.Post("https://example.test/post", byteArrayOf(1, 2, 4))

        assertEquals(first, sameContent)
        assertEquals(first.hashCode(), sameContent.hashCode())
        assertNotEquals(first, differentContent)
    }

    @Test
    fun withUrlPreservesHeadersForUrlContent() {
        val content =
            WebContent.Url(
                url = "https://example.test/old",
                additionalHttpHeaders = mapOf("X-Test" to "value"),
            )

        assertEquals(
            WebContent.Url(
                url = "https://example.test/new",
                additionalHttpHeaders = mapOf("X-Test" to "value"),
            ),
            content.withUrl("https://example.test/new"),
        )
    }

    @Test
    fun withUrlConvertsNonUrlContentToPlainUrlContent() {
        val content = WebContent.Data("<html></html>", baseUrl = "https://example.test/base")

        assertEquals(
            WebContent.Url("https://example.test/new"),
            content.withUrl("https://example.test/new"),
        )
    }
}
