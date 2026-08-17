package com.multiplatform.webview.web

import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.HTTPMethod
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

internal class WKUIDelegate(
    private val navigationHandler: WebViewNavigationHandler,
) : NSObject(),
    WKUIDelegateProtocol {
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        val url = forNavigationAction.request.URL?.absoluteString ?: return null
        val decision =
            navigationHandler.onNavigationRequest(
                WebViewNavigationRequest(
                    url = url,
                    method = forNavigationAction.request.HTTPMethod ?: "GET",
                    destination = WebViewNavigationDestination.NewWindow,
                    isRedirect = false,
                    hasUserGesture = null,
                ),
            )
        if (decision == WebViewNavigationDecision.Allow) {
            webView.loadRequest(forNavigationAction.request)
        }
        return null
    }
}
