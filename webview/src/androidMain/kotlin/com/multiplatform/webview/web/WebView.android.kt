package com.multiplatform.webview.web

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.multiplatform.webview.jsbridge.ConsoleBridge
import com.multiplatform.webview.jsbridge.WebViewJsBridge
import com.multiplatform.webview.request.AndroidWebViewSchemeAdapter
import com.multiplatform.webview.request.AndroidWebViewSchemeClient
import com.multiplatform.webview.request.WebViewSchemeConfig

/**
 * Android WebView implementation.
 */
@Composable
actual fun ActualWebView(
    state: WebViewState,
    modifier: Modifier,
    captureBackPresses: Boolean,
    navigator: WebViewNavigator,
    webViewJsBridge: WebViewJsBridge?,
    consoleBridge: ConsoleBridge?,
    onCreated: (NativeWebView) -> Unit,
    onDispose: (NativeWebView) -> Unit,
    platformWebViewParams: PlatformWebViewParams?,
    factory: (WebViewFactoryParam) -> NativeWebView,
    schemeConfig: WebViewSchemeConfig?,
    navigationHandler: WebViewNavigationHandler?,
) {
    require(schemeConfig == null || platformWebViewParams?.client == null) {
        "A custom Android WebViewClient cannot be used together with WebViewSchemeConfig"
    }
    val schemeAdapter = schemeConfig?.let { remember { AndroidWebViewSchemeAdapter(it) } }
    val client =
        schemeAdapter?.let { remember { AndroidWebViewSchemeClient(it) } }
            ?: platformWebViewParams?.client
            ?: remember { AccompanistWebViewClient() }
    AccompanistWebView(
        state,
        modifier,
        captureBackPresses,
        navigator,
        webViewJsBridge,
        consoleBridge,
        onCreated = { webView ->
            schemeAdapter?.installFetchBridge(webView)
            onCreated(webView)
        },
        onDispose = { webView ->
            schemeAdapter?.close()
            onDispose(webView)
        },
        client = client,
        chromeClient =
            platformWebViewParams?.chromeClient ?: remember { AccompanistWebChromeClient() },
        factory = { factory(WebViewFactoryParam(it)) },
        navigationHandler = navigationHandler,
    )
}

/** Android WebView factory parameters: a context. */
actual data class WebViewFactoryParam(
    val context: Context,
)

/** Default WebView factory for Android. */
actual fun defaultWebViewFactory(param: WebViewFactoryParam) = android.webkit.WebView(param.context)

@Immutable
actual data class PlatformWebViewParams(
    val client: AccompanistWebViewClient? = null,
    val chromeClient: AccompanistWebChromeClient? = null,
)
