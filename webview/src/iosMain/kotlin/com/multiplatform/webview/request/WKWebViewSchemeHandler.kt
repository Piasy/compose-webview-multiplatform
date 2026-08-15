package com.multiplatform.webview.request

import com.multiplatform.webview.request.internal.WebViewSchemeRequestCoordinator
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.allHTTPHeaderFields
import platform.Foundation.create
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class WKWebViewSchemeHandler(
    config: WebViewSchemeConfig,
) : NSObject(),
    WKURLSchemeHandlerProtocol {
    private val coordinator = WebViewSchemeRequestCoordinator(config)
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tasks = mutableMapOf<WKURLSchemeTaskProtocol, Job>()
    private var closed = false

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        startURLSchemeTask: WKURLSchemeTaskProtocol,
    ) {
        if (closed) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val request = startURLSchemeTask.request
                    val headers =
                        buildMap {
                            request.allHTTPHeaderFields?.forEach { (key, value) ->
                                put(key.toString(), value.toString())
                            }
                        }
                    val response =
                        coordinator.execute(
                            WebViewSchemeRequest(
                                url = request.URL?.absoluteString ?: "",
                                method = request.HTTPMethod ?: "GET",
                                headers = headers,
                            ),
                        )
                    if (!isActive(startURLSchemeTask)) return@launch
                    val url = requireNotNull(request.URL)
                    val nativeResponse =
                        NSHTTPURLResponse(
                            uRL = url,
                            statusCode = response.statusCode.toLong(),
                            HTTPVersion = "HTTP/1.1",
                            headerFields = response.headers.mapKeys { it.key as Any? },
                        )
                    startURLSchemeTask.didReceiveResponse(nativeResponse)
                    if (!isActive(startURLSchemeTask)) return@launch
                    if (response.body.isNotEmpty()) {
                        startURLSchemeTask.didReceiveData(response.body.toNSData())
                    }
                    if (!isActive(startURLSchemeTask)) return@launch
                    startURLSchemeTask.didFinish()
                } finally {
                    tasks.remove(startURLSchemeTask)
                }
            }
        tasks[startURLSchemeTask] = job
        job.start()
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        stopURLSchemeTask: WKURLSchemeTaskProtocol,
    ) {
        tasks.remove(stopURLSchemeTask)?.cancel()
    }

    fun close() {
        if (closed) return
        closed = true
        tasks.values.toList().forEach(Job::cancel)
        tasks.clear()
        coordinator.close()
        scope.cancel()
    }

    private fun isActive(task: WKURLSchemeTaskProtocol): Boolean = !closed && tasks.containsKey(task)

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) {
            NSData()
        } else {
            usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
            }
        }
}
