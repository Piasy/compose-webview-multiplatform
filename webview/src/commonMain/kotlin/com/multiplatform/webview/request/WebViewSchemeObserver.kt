package com.multiplatform.webview.request

data class WebViewSchemeRequestContext(
    val requestId: Long,
    val request: WebViewSchemeRequest,
)

interface WebViewSchemeObserver {
    fun onRequestReceived(context: WebViewSchemeRequestContext)

    fun onHandlerStarted(context: WebViewSchemeRequestContext)

    fun onRequestCompleted(
        context: WebViewSchemeRequestContext,
        outcome: WebViewSchemeOutcome,
        totalDurationMillis: Long,
        handlerDurationMillis: Long?,
    )
}

sealed interface WebViewSchemeOutcome {
    data class Response(
        val statusCode: Int,
        val reasonPhrase: String,
        val mimeType: String,
        val encoding: String?,
        val headers: Map<String, String>,
        val bodySize: Int,
    ) : WebViewSchemeOutcome

    data class UnsupportedMethod(
        val method: String,
    ) : WebViewSchemeOutcome

    data object QueueFull : WebViewSchemeOutcome

    data object Timeout : WebViewSchemeOutcome

    data class HandlerException(
        val throwable: Throwable,
    ) : WebViewSchemeOutcome

    data class InvalidResponse(
        val throwable: Throwable?,
    ) : WebViewSchemeOutcome

    data object Cancelled : WebViewSchemeOutcome
}
