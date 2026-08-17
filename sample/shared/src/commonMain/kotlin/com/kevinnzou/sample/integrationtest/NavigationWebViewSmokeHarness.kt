package com.kevinnzou.sample.integrationtest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.WebViewNavigationDecision
import com.multiplatform.webview.web.WebViewNavigationDestination
import com.multiplatform.webview.web.WebViewNavigationRequest
import com.multiplatform.webview.web.rememberWebViewStateWithHTMLData

data class NavigationWebViewSmokeSnapshot(
    val pageTitle: String?,
    val requests: List<WebViewNavigationRequest>,
) {
    fun currentMainFrameCount(url: String): Int = count(url, WebViewNavigationDestination.CurrentMainFrame)

    fun popupCount(url: String): Int = count(url, WebViewNavigationDestination.NewWindow)
}

private val navigationWebViewSmokeHtml =
    """
    <!doctype html>
    <html>
      <head><title>CWM Navigation Source</title></head>
      <body>
        <a id="current-link" href="https://example.test/current">Current</a>
        <a id="blank-link" target="_blank" href="https://example.test/blank">Blank</a>
        <button id="popup-button" onclick="window.open('https://example.test/popup')">Popup</button>
      </body>
    </html>
    """.trimIndent()

private val navigationWebViewNoHandlerHtml =
    """
    <!doctype html>
    <html>
      <head><title>CWM No Handler Source</title></head>
      <body><a id="blank-link" target="_blank" href="about:blank">Blank</a></body>
    </html>
    """.trimIndent()

@Composable
fun NavigationWebViewSmokeHarness(onSnapshot: (NavigationWebViewSmokeSnapshot) -> Unit = {}) {
    val state = rememberWebViewStateWithHTMLData(navigationWebViewSmokeHtml)
    val requests = remember { mutableListOf<WebViewNavigationRequest>() }

    LaunchedEffect(state) {
        snapshotFlow { state.pageTitle }.collect { title ->
            onSnapshot(NavigationWebViewSmokeSnapshot(title, requests.toList()))
        }
    }

    WebView(
        state = state,
        navigationHandler = { request ->
            if (request.url.startsWith("https://example.test/")) {
                requests += request
                onSnapshot(NavigationWebViewSmokeSnapshot(state.pageTitle, requests.toList()))
                WebViewNavigationDecision.Cancel
            } else {
                WebViewNavigationDecision.Allow
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun NavigationWebViewNoHandlerHarness() {
    WebView(
        state = rememberWebViewStateWithHTMLData(navigationWebViewNoHandlerHtml),
        modifier = Modifier.fillMaxSize(),
    )
}

fun NavigationWebViewSmokeSnapshot.count(
    url: String,
    destination: WebViewNavigationDestination,
): Int = requests.count { it.url == url && it.destination == destination }
