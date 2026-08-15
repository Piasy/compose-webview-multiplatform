package com.multiplatform.webview.request.internal

import com.multiplatform.webview.request.WebViewSchemeResponse

internal fun syntheticSchemeResponse(
    statusCode: Int,
    body: String,
    isHead: Boolean,
    extraHeaders: Map<String, String> = emptyMap(),
): WebViewSchemeResponse {
    val bodyBytes = body.encodeToByteArray()
    return WebViewSchemeResponse(
        body = if (isHead) byteArrayOf() else bodyBytes,
        mimeType = "text/plain",
        encoding = "utf-8",
        statusCode = statusCode,
        reasonPhrase = defaultReasonPhrase(statusCode),
        headers =
            buildMap {
                putAll(extraHeaders)
                put("Content-Type", "text/plain; charset=utf-8")
                put("Content-Length", bodyBytes.size.toString())
            },
    )
}

internal fun disposedSchemeResponse(): WebViewSchemeResponse =
    WebViewSchemeResponse(
        mimeType = "text/plain",
        encoding = "utf-8",
        statusCode = 503,
        reasonPhrase = "Service Unavailable",
        headers =
            mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to "0",
            ),
    )
