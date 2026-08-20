package com.multiplatform.webview

import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kevinnzou.sample.integrationtest.NavigationWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.NavigationWebViewSmokeSnapshot
import com.kevinnzou.sample.integrationtest.NavigatorSchemeWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.REAL_WEB_VIEW_SMOKE_EXPECTED_PROBE
import com.kevinnzou.sample.integrationtest.REAL_WEB_VIEW_SMOKE_TITLE
import com.kevinnzou.sample.integrationtest.RealWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.RealWebViewSmokeSnapshot
import com.kevinnzou.sample.integrationtest.SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE
import com.kevinnzou.sample.integrationtest.SCHEME_WEB_VIEW_SMOKE_TITLE
import com.kevinnzou.sample.integrationtest.STATIC_SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE
import com.kevinnzou.sample.integrationtest.STATIC_SCHEME_WEB_VIEW_SMOKE_TITLE
import com.kevinnzou.sample.integrationtest.SchemeCancellationHarness
import com.kevinnzou.sample.integrationtest.SchemeCancellationSnapshot
import com.kevinnzou.sample.integrationtest.SchemeWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.SchemeWebViewSmokeSnapshot
import com.kevinnzou.sample.integrationtest.StaticSchemeWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.count
import com.multiplatform.webview.web.WebViewNavigationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealWebViewSmokeTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun testRealWebViewLoadsInlineHtmlAndEvaluatesJavascript() {
        val snapshots = LinkedBlockingQueue<RealWebViewSmokeSnapshot>()
        setSmokeContent(onSnapshot = snapshots::offer)

        val ready = awaitReadySnapshot(snapshots, minimumLoadCount = 1)

        assertReadySnapshot(ready)
    }

    @Test
    fun testRealWebViewLoadsCustomSchemeNavigationAndSubresources() {
        val snapshots = LinkedBlockingQueue<SchemeWebViewSmokeSnapshot>()
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SchemeWebViewSmokeHarness(onSnapshot = snapshots::offer)
            }
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var latest: SchemeWebViewSmokeSnapshot? = null
        while (System.nanoTime() < deadline) {
            val current = snapshots.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            latest = current
            if (normalizeProbe(current.jsProbeResult) == SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE) {
                break
            }
        }
        val snapshot = requireNotNull(latest)
        val diagnostic = snapshot.toString()
        assertEquals(diagnostic, "Finished", snapshot.loadingState)
        assertEquals(diagnostic, SCHEME_WEB_VIEW_SMOKE_TITLE, snapshot.pageTitle)
        assertEquals(diagnostic, SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE, normalizeProbe(snapshot.jsProbeResult))
        assertTrue(diagnostic, snapshot.errorsForCurrentRequest.isEmpty())
        assertTrue(diagnostic, snapshot.requestUrls.contains("cwmtest://host/index.html?source=smoke"))
        assertTrue(diagnostic, snapshot.requestUrls.any { it.endsWith("/fetch?source=js") })
        assertTrue(diagnostic, snapshot.requestMethods.contains("HEAD"))
        assertTrue(diagnostic, snapshot.completedStatuses.contains(404))
    }

    @Test
    fun testStaticCustomSchemeLoadsWithoutFetchBridgeCapabilities() {
        val snapshots = LinkedBlockingQueue<SchemeWebViewSmokeSnapshot>()
        val setupFailures = AtomicInteger(0)
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                StaticSchemeWebViewSmokeHarness(
                    onSchemeSetupFailed = { setupFailures.incrementAndGet() },
                    onSnapshot = snapshots::offer,
                )
            }
        }

        val snapshot = awaitSchemeReadySnapshot(
            snapshots = snapshots,
            expectedProbe = STATIC_SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE,
        )
        val diagnostic = snapshot.toString()
        assertEquals(diagnostic, "Finished", snapshot.loadingState)
        assertEquals(diagnostic, STATIC_SCHEME_WEB_VIEW_SMOKE_TITLE, snapshot.pageTitle)
        assertEquals(
            diagnostic,
            STATIC_SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE,
            normalizeProbe(snapshot.jsProbeResult),
        )
        assertTrue(diagnostic, snapshot.errorsForCurrentRequest.isEmpty())
        assertTrue(diagnostic, snapshot.requestUrls.contains("cwmtest://host/index.html?source=smoke"))
        assertTrue(diagnostic, snapshot.requestUrls.none { it.contains("/fetch") || it.contains("/xhr") })
        assertEquals(diagnostic, 0, setupFailures.get())
    }

    @Test
    fun testCustomSchemeDiagnosticsReportProviderCapabilitiesAndReturnedResponse() {
        executeShellCommand("logcat -c")
        val snapshots = LinkedBlockingQueue<SchemeWebViewSmokeSnapshot>()
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SchemeWebViewSmokeHarness(onSnapshot = snapshots::offer)
            }
        }

        val snapshot = awaitSchemeMainResponse(snapshots)
        val logs = executeShellCommand("logcat -d -v brief -s ComposeWebView:I")

        assertTrue(logs, logs.contains("operation=custom_scheme_setup stage=capability_check"))
        assertTrue(logs, logs.contains("provider_package="))
        assertTrue(logs, logs.contains("web_message_listener_supported="))
        assertTrue(logs, logs.contains("document_start_script_supported="))
        assertTrue(snapshot.toString(), snapshot.requestUrls.isNotEmpty())
        assertTrue(logs, logs.contains("operation=custom_scheme_request stage=response_returned"))
    }

    @Test
    fun testNavigatorLoadsInitialCustomSchemeNavigationAndSubresources() {
        val snapshots = LinkedBlockingQueue<SchemeWebViewSmokeSnapshot>()
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                NavigatorSchemeWebViewSmokeHarness(onSnapshot = snapshots::offer)
            }
        }

        val snapshot = awaitSchemeReadySnapshot(
            snapshots = snapshots,
            expectedProbe = SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE,
        )
        val diagnostic = snapshot.toString()
        assertEquals(diagnostic, "Finished", snapshot.loadingState)
        assertEquals(diagnostic, SCHEME_WEB_VIEW_SMOKE_TITLE, snapshot.pageTitle)
        assertEquals(diagnostic, SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE, normalizeProbe(snapshot.jsProbeResult))
        assertTrue(diagnostic, snapshot.requestUrls.contains("cwmtest://host/index.html?source=smoke"))
    }

    @Test
    fun testDisposingWebViewCancelsRunningSchemeRequestExactlyOnce() {
        val snapshots = LinkedBlockingQueue<SchemeCancellationSnapshot>()
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                SchemeCancellationHarness(onSnapshot = snapshots::offer)
            }
        }
        val received = snapshots.poll(10, TimeUnit.SECONDS)
        assertEquals(true, received?.received)

        activityRule.scenario.onActivity { activity ->
            activity.setContent {}
        }

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var latest = received
        while (System.nanoTime() < deadline && latest?.cancelledCompletions != 1) {
            latest = snapshots.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: latest
        }
        assertEquals(latest.toString(), 1, latest?.cancelledCompletions)
        Thread.sleep(200)
        val duplicate = snapshots.poll()
        assertTrue(duplicate?.toString() ?: "no duplicate completion", duplicate == null || duplicate.cancelledCompletions == 1)
    }

    @Test
    fun testNavigationHandlerCancelsCurrentAndNewWindowNavigationsExactlyOnce() {
        val snapshots = LinkedBlockingQueue<NavigationWebViewSmokeSnapshot>()
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                NavigationWebViewSmokeHarness(onSnapshot = snapshots::offer)
            }
        }

        val webView = awaitWebView()
        awaitNavigationSnapshot(snapshots) { it.pageTitle == "CWM Navigation Source" }
        evaluateJavascript(webView, "document.getElementById('current-link').click()")
        awaitNavigationSnapshot(snapshots) { it.requests.size == 1 }
        evaluateJavascript(webView, "document.getElementById('blank-link').click()")
        awaitNavigationSnapshot(snapshots) { it.requests.size == 2 }
        evaluateJavascript(webView, "document.getElementById('popup-button').click()")
        val snapshot = awaitNavigationSnapshot(snapshots) { it.requests.size == 3 }
        assertEquals(
            snapshot.toString(),
            1,
            snapshot.count("https://example.test/current", WebViewNavigationDestination.CurrentMainFrame),
        )
        assertEquals(
            snapshot.toString(),
            1,
            snapshot.count("https://example.test/blank", WebViewNavigationDestination.NewWindow),
        )
        assertEquals(
            snapshot.toString(),
            1,
            snapshot.count("https://example.test/popup", WebViewNavigationDestination.NewWindow),
        )
    }

    private fun setSmokeContent(onSnapshot: (RealWebViewSmokeSnapshot) -> Unit) {
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                RealWebViewSmokeHarness(onSnapshot = onSnapshot)
            }
        }
    }

    private fun awaitSchemeReadySnapshot(
        snapshots: LinkedBlockingQueue<SchemeWebViewSmokeSnapshot>,
        expectedProbe: String,
    ): SchemeWebViewSmokeSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var latest: SchemeWebViewSmokeSnapshot? = null
        while (System.nanoTime() < deadline) {
            val current = snapshots.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            latest = current
            if (normalizeProbe(current.jsProbeResult) == expectedProbe) {
                return current
            }
        }
        error("Timed out waiting for navigator custom scheme load: $latest")
    }

    private fun awaitSchemeMainResponse(
        snapshots: LinkedBlockingQueue<SchemeWebViewSmokeSnapshot>,
    ): SchemeWebViewSmokeSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var latest: SchemeWebViewSmokeSnapshot? = null
        while (System.nanoTime() < deadline) {
            val current = snapshots.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            latest = current
            if (current.loadingState == "Finished" && current.requestUrls.isNotEmpty()) return current
        }
        error("Timed out waiting for custom-scheme main response: $latest")
    }

    private fun executeShellCommand(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return descriptor.use {
            FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }

    private fun awaitReadySnapshot(
        snapshots: LinkedBlockingQueue<RealWebViewSmokeSnapshot>,
        minimumLoadCount: Int,
    ): RealWebViewSmokeSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var latest: RealWebViewSmokeSnapshot? = null
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val current = snapshots.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            latest = current
            if (
                current.completedLoadCount >= minimumLoadCount &&
                normalizeProbe(current.jsProbeResult) == REAL_WEB_VIEW_SMOKE_EXPECTED_PROBE
            ) {
                return current
            }
        }
        error("Timed out waiting for real WebView smoke result. ${latest?.diagnostic()}")
    }

    private fun assertReadySnapshot(snapshot: RealWebViewSmokeSnapshot) {
        val diagnostic = snapshot.diagnostic()
        assertEquals(diagnostic, "Finished", snapshot.loadingState)
        assertEquals(diagnostic, REAL_WEB_VIEW_SMOKE_TITLE, snapshot.pageTitle)
        assertTrue(diagnostic, snapshot.errorsForCurrentRequest.isEmpty())
        assertEquals(
            diagnostic,
            REAL_WEB_VIEW_SMOKE_EXPECTED_PROBE,
            normalizeProbe(snapshot.jsProbeResult),
        )
    }

    private fun normalizeProbe(rawResult: String?): String? = rawResult?.trim()?.removeSurrounding("\"")

    private fun awaitNavigationSnapshot(
        snapshots: LinkedBlockingQueue<NavigationWebViewSmokeSnapshot>,
        predicate: (NavigationWebViewSmokeSnapshot) -> Boolean,
    ): NavigationWebViewSmokeSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var latest: NavigationWebViewSmokeSnapshot? = null
        while (System.nanoTime() < deadline) {
            val current = snapshots.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) ?: break
            latest = current
            if (predicate(current)) return current
        }
        error("Timed out waiting for navigation requests: $latest")
    }

    private fun awaitWebView(): android.webkit.WebView {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var found: android.webkit.WebView? = null
            activityRule.scenario.onActivity { activity ->
                found = findWebView(activity.window.decorView)
            }
            found?.let { return it }
            Thread.sleep(50)
        }
        error("Timed out waiting for WebView")
    }

    private fun evaluateJavascript(
        webView: android.webkit.WebView,
        script: String,
    ) {
        activityRule.scenario.onActivity { webView.evaluateJavascript(script, null) }
    }

    private fun findWebView(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun RealWebViewSmokeSnapshot.diagnostic(): String =
        "loadingState=$loadingState, lastLoadedUrl=$lastLoadedUrl, pageTitle=$pageTitle, " +
            "errorsForCurrentRequest=$errorsForCurrentRequest, jsProbeResult=$jsProbeResult"
}
