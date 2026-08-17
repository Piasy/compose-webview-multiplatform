package com.multiplatform.webview.web

/** Observes top-level document navigations before the platform WebView handles them. */
fun interface WebViewNavigationHandler {
    fun onNavigationRequest(request: WebViewNavigationRequest): WebViewNavigationDecision
}

/** Subresources and iframe navigations are intentionally excluded from this request. */
data class WebViewNavigationRequest(
    val url: String,
    val method: String = "GET",
    val destination: WebViewNavigationDestination,
    val isRedirect: Boolean = false,
    val hasUserGesture: Boolean? = null,
)

enum class WebViewNavigationDestination {
    CurrentMainFrame,
    NewWindow,
}

enum class WebViewNavigationDecision {
    Allow,
    Cancel,
}
