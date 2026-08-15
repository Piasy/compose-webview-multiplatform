package com.kevinnzou.sample.integrationtest

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewStateWithHTMLData

const val REAL_WEB_VIEW_SMOKE_TITLE = "CWM Real WebView Ready"
const val REAL_WEB_VIEW_SMOKE_EXPECTED_PROBE = "ready|42"

private const val REAL_WEB_VIEW_SMOKE_PROBE =
    "window.__cwmSmokeResult.dom + '|' + window.__cwmSmokeResult.arithmetic"

private val realWebViewSmokeHtml =
    """
    <!doctype html>
    <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$REAL_WEB_VIEW_SMOKE_TITLE</title>
      </head>
      <body>
        <div id="smoke-status">starting</div>
        <script>
          document.getElementById("smoke-status").textContent = "ready";
          window.__cwmSmokeResult = {
            dom: document.getElementById("smoke-status").textContent,
            arithmetic: 6 * 7
          };
        </script>
      </body>
    </html>
    """.trimIndent()

data class RealWebViewSmokeSnapshot(
    val loadingState: String,
    val lastLoadedUrl: String?,
    val pageTitle: String?,
    val errorsForCurrentRequest: List<String>,
    val jsProbeResult: String?,
    val completedLoadCount: Int,
)

private data class ObservedWebViewState(
    val loadingState: LoadingState,
    val lastLoadedUrl: String?,
    val pageTitle: String?,
    val errorsForCurrentRequest: List<String>,
)

@Composable
fun RealWebViewSmokeHarness(onSnapshot: (RealWebViewSmokeSnapshot) -> Unit = {}) {
    val state =
        rememberWebViewStateWithHTMLData(
            data = realWebViewSmokeHtml,
            mimeType = "text/html",
        )
    val navigator = rememberWebViewNavigator()
    val completedLoads = remember { intArrayOf(0) }

    LaunchedEffect(state, navigator) {
        var evaluatedCurrentLoad = false
        snapshotFlow {
            ObservedWebViewState(
                loadingState = state.loadingState,
                lastLoadedUrl = state.lastLoadedUrl,
                pageTitle = state.pageTitle,
                errorsForCurrentRequest = state.errorsForCurrentRequest.map { it.toString() },
            )
        }.collect { observed ->
            if (observed.loadingState !is LoadingState.Finished) {
                evaluatedCurrentLoad = false
                onSnapshot(observed.toSnapshot(completedLoads[0], null))
            } else if (observed.pageTitle == REAL_WEB_VIEW_SMOKE_TITLE && !evaluatedCurrentLoad) {
                evaluatedCurrentLoad = true
                navigator.evaluateJavaScript(REAL_WEB_VIEW_SMOKE_PROBE) { rawResult ->
                    completedLoads[0] += 1
                    onSnapshot(observed.toSnapshot(completedLoads[0], rawResult))
                }
            }
        }
    }

    WebView(
        state = state,
        navigator = navigator,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun ObservedWebViewState.toSnapshot(
    completedLoadCount: Int,
    jsProbeResult: String?,
) = RealWebViewSmokeSnapshot(
    loadingState = loadingState.toString(),
    lastLoadedUrl = lastLoadedUrl,
    pageTitle = pageTitle,
    errorsForCurrentRequest = errorsForCurrentRequest,
    jsProbeResult = jsProbeResult,
    completedLoadCount = completedLoadCount,
)
