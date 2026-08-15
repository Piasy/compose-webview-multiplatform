package com.kevinnzou.sample.integrationtest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.multiplatform.webview.request.WebViewSchemeConfig
import com.multiplatform.webview.request.WebViewSchemeObserver
import com.multiplatform.webview.request.WebViewSchemeOutcome
import com.multiplatform.webview.request.WebViewSchemeRegistration
import com.multiplatform.webview.request.WebViewSchemeRequest
import com.multiplatform.webview.request.WebViewSchemeRequestContext
import com.multiplatform.webview.request.WebViewSchemeResponse
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.awaitCancellation

const val SCHEME_WEB_VIEW_SMOKE_TITLE = "CWM Scheme Ready"
const val SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE =
    "styled|script|image|frame|fetch|xhr|head-ok|0|404"

private const val SCHEME_WEB_VIEW_SMOKE_URL = "cwmtest://host/index.html?source=smoke"
private const val SCHEME_WEB_VIEW_SMOKE_PROBE = "window.__cwmSchemeResult"

data class SchemeWebViewSmokeSnapshot(
    val loadingState: String,
    val lastLoadedUrl: String?,
    val pageTitle: String?,
    val errorsForCurrentRequest: List<String>,
    val jsProbeResult: String?,
    val requestUrls: List<String>,
    val requestMethods: List<String>,
    val completedStatuses: List<Int>,
)

private data class ObservedSchemeWebViewState(
    val loadingState: LoadingState,
    val lastLoadedUrl: String?,
    val pageTitle: String?,
    val errorsForCurrentRequest: List<String>,
)

@Composable
fun SchemeWebViewSmokeHarness(onSnapshot: (SchemeWebViewSmokeSnapshot) -> Unit = {}) {
    val observer = remember { SchemeSmokeObserver() }
    val config =
        remember {
            WebViewSchemeConfig(
                registrations =
                    listOf(
                        WebViewSchemeRegistration("cwmtest") { request ->
                            schemeSmokeResponse(request)
                        },
                        WebViewSchemeRegistration("cwmalt") { request ->
                            schemeSmokeResponse(request)
                        },
                    ),
                observer = observer,
            )
        }
    val state = rememberWebViewState(SCHEME_WEB_VIEW_SMOKE_URL)
    val navigator = rememberWebViewNavigator()

    LaunchedEffect(state, navigator) {
        var evaluatedReadyPage = false
        snapshotFlow {
            ObservedSchemeWebViewState(
                loadingState = state.loadingState,
                lastLoadedUrl = state.lastLoadedUrl,
                pageTitle = state.pageTitle,
                errorsForCurrentRequest = state.errorsForCurrentRequest.map { it.toString() },
            )
        }.collect { observed ->
            if (
                observed.loadingState is LoadingState.Finished &&
                observed.pageTitle == SCHEME_WEB_VIEW_SMOKE_TITLE &&
                !evaluatedReadyPage
            ) {
                evaluatedReadyPage = true
                navigator.evaluateJavaScript(SCHEME_WEB_VIEW_SMOKE_PROBE) { result ->
                    onSnapshot(observed.toSnapshot(result, observer))
                }
            } else {
                onSnapshot(observed.toSnapshot(null, observer))
            }
        }
    }

    WebView(
        state = state,
        schemeConfig = config,
        navigator = navigator,
        modifier = Modifier.fillMaxSize(),
    )
}

data class SchemeCancellationSnapshot(
    val received: Boolean,
    val cancelledCompletions: Int,
)

@Composable
fun SchemeCancellationHarness(onSnapshot: (SchemeCancellationSnapshot) -> Unit) {
    val observer = remember { SchemeCancellationObserver(onSnapshot) }
    val config =
        remember {
            WebViewSchemeConfig(
                registrations =
                    listOf(
                        WebViewSchemeRegistration("cwmcancel", timeoutMillis = 30_000) {
                            awaitCancellation()
                        },
                    ),
                observer = observer,
            )
        }
    val state = rememberWebViewState("cwmcancel://host/wait")

    WebView(
        state = state,
        schemeConfig = config,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun ObservedSchemeWebViewState.toSnapshot(
    jsProbeResult: String?,
    observer: SchemeSmokeObserver,
): SchemeWebViewSmokeSnapshot {
    val observerSnapshot = observer.snapshot()
    return SchemeWebViewSmokeSnapshot(
        loadingState = loadingState.toString(),
        lastLoadedUrl = lastLoadedUrl,
        pageTitle = pageTitle,
        errorsForCurrentRequest = errorsForCurrentRequest,
        jsProbeResult = jsProbeResult,
        requestUrls = observerSnapshot.urls,
        requestMethods = observerSnapshot.methods,
        completedStatuses = observerSnapshot.statuses,
    )
}

private fun schemeSmokeResponse(request: WebViewSchemeRequest): WebViewSchemeResponse {
    val path = request.url.substringAfter("://host").substringBefore('?')
    return when (path) {
        "/index.html" -> textResponse(SCHEME_HTML, "text/html", headers = mapOf("X-Main" to "yes"))
        "/style.css" -> textResponse(":root { --scheme-ready: styled; }", "text/css")
        "/script.js" -> textResponse("window.__externalScript = 'script';", "text/javascript")
        "/image.svg" -> textResponse(SCHEME_SVG, "image/svg+xml")
        "/frame.html" -> textResponse("<body id='frame-value'>frame</body>", "text/html")
        "/fetch" ->
            textResponse(
                "fetch",
                "text/plain",
                headers =
                    mapOf(
                        "X-Fetch" to "yes",
                        "Access-Control-Allow-Origin" to "*",
                    ),
            )
        "/xhr" -> textResponse("xhr", "text/plain")
        "/head" -> textResponse("head-body-must-not-be-delivered", "text/plain", headers = mapOf("X-Head" to "head-ok"))
        "/missing" -> textResponse("missing", "text/plain", statusCode = 404)
        else -> textResponse("unknown", "text/plain", statusCode = 404)
    }
}

private fun textResponse(
    body: String,
    mimeType: String,
    statusCode: Int = 200,
    headers: Map<String, String> = emptyMap(),
) = WebViewSchemeResponse(
    body = body.encodeToByteArray(),
    mimeType = mimeType,
    encoding = "utf-8",
    statusCode = statusCode,
    headers = headers,
)

private val SCHEME_HTML =
    """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>CWM Scheme Loading</title>
        <link rel="stylesheet" href="/style.css">
        <script src="/script.js"></script>
      </head>
      <body>
        <img id="scheme-image" src="/image.svg">
        <iframe id="scheme-frame" src="/frame.html"></iframe>
        <script>
          Promise.all([
            new Promise((resolve, reject) => {
              const image = document.getElementById('scheme-image');
              image.onload = () => resolve('image');
              image.onerror = reject;
            }),
            new Promise((resolve, reject) => {
              const frame = document.getElementById('scheme-frame');
              frame.onload = () => resolve(frame.contentDocument.getElementById('frame-value').textContent);
              frame.onerror = reject;
            }),
            fetch('cwmalt://host/fetch?source=js').then(response => response.text()),
            new Promise((resolve, reject) => {
              const xhr = new XMLHttpRequest();
              xhr.open('GET', '/xhr');
              xhr.onload = () => resolve(xhr.responseText);
              xhr.onerror = reject;
              xhr.send();
            }),
            fetch('/head', { method: 'HEAD' }).then(async response =>
              response.headers.get('X-Head') + '|' + (await response.text()).length
            ),
            fetch('/missing').then(response => response.status.toString())
          ]).then(values => {
            const style = getComputedStyle(document.documentElement).getPropertyValue('--scheme-ready').trim();
            window.__cwmSchemeResult = [
              style, window.__externalScript, values[0], values[1], values[2], values[3], values[4], values[5]
            ].join('|');
            document.title = '$SCHEME_WEB_VIEW_SMOKE_TITLE';
          }).catch(error => {
            window.__cwmSchemeResult = 'error:' + error;
            document.title = '$SCHEME_WEB_VIEW_SMOKE_TITLE';
          });
        </script>
      </body>
    </html>
    """.trimIndent()

private const val SCHEME_SVG =
    "<svg xmlns='http://www.w3.org/2000/svg' width='2' height='2'><rect width='2' height='2' fill='green'/></svg>"

private data class SchemeObserverSnapshot(
    val urls: List<String>,
    val methods: List<String>,
    val statuses: List<Int>,
)

private class SchemeSmokeObserver :
    SynchronizedObject(),
    WebViewSchemeObserver {
    private val urls = mutableListOf<String>()
    private val methods = mutableListOf<String>()
    private val statuses = mutableListOf<Int>()

    override fun onRequestReceived(context: WebViewSchemeRequestContext) =
        synchronized(this) {
            urls += context.request.url
            methods += context.request.method
        }

    override fun onHandlerStarted(context: WebViewSchemeRequestContext) = Unit

    override fun onRequestCompleted(
        context: WebViewSchemeRequestContext,
        outcome: WebViewSchemeOutcome,
        totalDurationMillis: Long,
        handlerDurationMillis: Long?,
    ) = synchronized(this) {
        if (outcome is WebViewSchemeOutcome.Response) {
            statuses += outcome.statusCode
        }
    }

    fun snapshot(): SchemeObserverSnapshot =
        synchronized(this) {
            SchemeObserverSnapshot(urls.toList(), methods.toList(), statuses.toList())
        }
}

private class SchemeCancellationObserver(
    private val onSnapshot: (SchemeCancellationSnapshot) -> Unit,
) : WebViewSchemeObserver {
    private var received = false
    private var cancelledCompletions = 0

    override fun onRequestReceived(context: WebViewSchemeRequestContext) {
        received = true
        onSnapshot(SchemeCancellationSnapshot(received, cancelledCompletions))
    }

    override fun onHandlerStarted(context: WebViewSchemeRequestContext) = Unit

    override fun onRequestCompleted(
        context: WebViewSchemeRequestContext,
        outcome: WebViewSchemeOutcome,
        totalDurationMillis: Long,
        handlerDurationMillis: Long?,
    ) {
        if (outcome is WebViewSchemeOutcome.Cancelled) {
            cancelledCompletions += 1
        }
        onSnapshot(SchemeCancellationSnapshot(received, cancelledCompletions))
    }
}
