package com.multiplatform.webview.web

import com.multiplatform.webview.request.WKWebViewSchemeHandler
import com.multiplatform.webview.request.WebViewSchemeConfig
import com.multiplatform.webview.request.WebViewSchemeRegistration
import com.multiplatform.webview.request.WebViewSchemeResponse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(ExperimentalForeignApi::class)
class WebViewSchemeFactoryTest {
    @Test
    fun customFactoryMustUseConfigurationContainingEveryRegisteredHandler() {
        val schemeConfig =
            WebViewSchemeConfig(
                registrations =
                    listOf(
                        WebViewSchemeRegistration("first") {
                            WebViewSchemeResponse(mimeType = "text/plain")
                        },
                        WebViewSchemeRegistration("second") {
                            WebViewSchemeResponse(mimeType = "text/plain")
                        },
                    ),
            )
        val handler = WKWebViewSchemeHandler(schemeConfig)
        val configuredWebView =
            WKWebView(
                frame = CGRectZero.readValue(),
                configuration =
                    WKWebViewConfiguration().apply {
                        schemeConfig.registrations.forEach { registration ->
                            setURLSchemeHandler(handler, registration.scheme)
                        }
                    },
            )
        validateSchemeFactoryResult(configuredWebView, schemeConfig, handler)

        val unconfiguredWebView =
            WKWebView(
                frame = CGRectZero.readValue(),
                configuration = WKWebViewConfiguration(),
            )
        assertFailsWith<IllegalArgumentException> {
            validateSchemeFactoryResult(unconfiguredWebView, schemeConfig, handler)
        }
        handler.close()
    }
}
