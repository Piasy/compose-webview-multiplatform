package com.multiplatform.webview.request

import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.multiplatform.webview.request.internal.WebViewSchemeRequestCoordinator
import com.multiplatform.webview.web.AccompanistWebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

internal class AndroidWebViewSchemeAdapter(
    private val config: WebViewSchemeConfig,
) {
    private val coordinator = WebViewSchemeRequestCoordinator(config)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var webView: WebView? = null
    private var scriptHandler: ScriptHandler? = null

    fun handles(request: WebResourceRequest): Boolean = request.url.scheme?.let(coordinator::handlesScheme) == true

    fun handle(request: WebResourceRequest): WebResourceResponse {
        val response =
            runBlocking {
                coordinator.execute(
                    WebViewSchemeRequest(
                        url = request.url.toString(),
                        method = request.method ?: "GET",
                        headers = request.requestHeaders ?: emptyMap(),
                    ),
                )
            }
        return WebResourceResponse(
            response.mimeType,
            response.encoding,
            response.statusCode,
            requireNotNull(response.reasonPhrase),
            response.headers,
            ByteArrayInputStream(response.body),
        )
    }

    fun installFetchBridge(webView: WebView) {
        if (this.webView != null) return
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "This Android WebView does not support custom-scheme fetch() bridging"
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "This Android WebView does not support document-start scripts"
        }
        val originRules = config.registrations.mapTo(mutableSetOf()) { "${it.scheme.lowercase()}://" }
        WebViewCompat.addWebMessageListener(
            webView,
            FETCH_BRIDGE_NAME,
            originRules,
        ) { _, message, _, _, replyProxy ->
            val requestJson = JSONObject(message.data ?: return@addWebMessageListener)
            val headersJson = requestJson.getJSONObject("headers")
            val headers =
                buildMap {
                    headersJson.keys().forEach { name -> put(name, headersJson.getString(name)) }
                }
            scope.launch {
                val response =
                    coordinator.execute(
                        WebViewSchemeRequest(
                            url = requestJson.getString("url"),
                            method = requestJson.getString("method"),
                            headers = headers,
                        ),
                    )
                replyProxy.postMessage(response.toJson(requestJson.getLong("id")).toString())
            }
        }
        scriptHandler =
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                FETCH_BRIDGE_SCRIPT.replace(
                    REGISTERED_SCHEMES_PLACEHOLDER,
                    JSONArray(config.registrations.map { it.scheme.lowercase() }).toString(),
                ),
                originRules,
            )
        this.webView = webView
    }

    fun close() {
        webView?.let { WebViewCompat.removeWebMessageListener(it, FETCH_BRIDGE_NAME) }
        scriptHandler?.remove()
        webView = null
        scriptHandler = null
        coordinator.close()
        scope.cancel()
    }

    private fun WebViewSchemeResponse.toJson(requestId: Long): JSONObject =
        JSONObject()
            .put("id", requestId)
            .put("status", statusCode)
            .put("reason", reasonPhrase)
            .put("headers", JSONObject(headers))
            .put("body", Base64.encodeToString(body, Base64.NO_WRAP))

    private companion object {
        const val FETCH_BRIDGE_NAME = "__cwmSchemeFetchBridge"
        const val REGISTERED_SCHEMES_PLACEHOLDER = "__CWM_REGISTERED_SCHEMES__"

        val FETCH_BRIDGE_SCRIPT =
            """
            (() => {
              const nativeFetch = window.fetch.bind(window);
              const registeredSchemes = new Set(__CWM_REGISTERED_SCHEMES__);
              let nextRequestId = 0;
              const pendingRequests = new Map();
              window.__cwmSchemeFetchBridge.onmessage = event => {
                const response = JSON.parse(event.data);
                const pending = pendingRequests.get(response.id);
                if (!pending) return;
                pendingRequests.delete(response.id);
                try {
                  const binary = atob(response.body);
                  const bytes = new Uint8Array(binary.length);
                  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
                  const body = pending.method === 'HEAD' || response.status === 204 || response.status === 205 ? null : bytes;
                  pending.resolve(new Response(body, {
                    status: response.status, statusText: response.reason, headers: response.headers
                  }));
                } catch (error) {
                  pending.reject(error);
                }
              };
              window.fetch = (input, init = {}) => {
                const rawUrl = typeof input === 'string' || input instanceof URL ? input.toString() : input.url;
                const url = new URL(rawUrl, document.baseURI);
                if (!registeredSchemes.has(url.protocol.slice(0, -1))) return nativeFetch(input, init);
                const method = (init.method || (input && input.method) || 'GET').toUpperCase();
                const headers = {};
                new Headers(init.headers || (input && input.headers) || undefined).forEach((value, name) => {
                  headers[name] = value;
                });
                return new Promise((resolve, reject) => {
                  const id = ++nextRequestId;
                  pendingRequests.set(id, { resolve: resolve, reject: reject, method: method });
                  window.__cwmSchemeFetchBridge.postMessage(JSON.stringify({
                    id: id, url: url.href, method: method, headers: headers
                  }));
                });
              };
            })();
            """.trimIndent()
    }
}

internal class AndroidWebViewSchemeClient(
    private val adapter: AndroidWebViewSchemeAdapter,
) : AccompanistWebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? =
        if (request != null && adapter.handles(request)) {
            adapter.handle(request)
        } else {
            super.shouldInterceptRequest(view, request)
        }
}
