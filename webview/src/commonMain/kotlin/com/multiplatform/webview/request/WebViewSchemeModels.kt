package com.multiplatform.webview.request

data class WebViewSchemeRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
)

data class WebViewSchemeResponse(
    val body: ByteArray = byteArrayOf(),
    val mimeType: String,
    val encoding: String? = null,
    val statusCode: Int = 200,
    val reasonPhrase: String? = null,
    val headers: Map<String, String> = emptyMap(),
)
