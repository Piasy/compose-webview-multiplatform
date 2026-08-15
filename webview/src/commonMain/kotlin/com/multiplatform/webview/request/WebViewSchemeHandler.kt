package com.multiplatform.webview.request

fun interface WebViewSchemeHandler {
    suspend fun handle(request: WebViewSchemeRequest): WebViewSchemeResponse
}
