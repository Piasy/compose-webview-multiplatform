package com.multiplatform.webview

import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.kevinnzou.sample.integrationtest.REAL_WEB_VIEW_SMOKE_EXPECTED_PROBE
import com.kevinnzou.sample.integrationtest.REAL_WEB_VIEW_SMOKE_TITLE
import com.kevinnzou.sample.integrationtest.RealWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.RealWebViewSmokeSnapshot
import com.kevinnzou.sample.integrationtest.SCHEME_WEB_VIEW_SMOKE_EXPECTED_PROBE
import com.kevinnzou.sample.integrationtest.SCHEME_WEB_VIEW_SMOKE_TITLE
import com.kevinnzou.sample.integrationtest.SchemeCancellationHarness
import com.kevinnzou.sample.integrationtest.SchemeCancellationSnapshot
import com.kevinnzou.sample.integrationtest.SchemeWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.SchemeWebViewSmokeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

    private fun setSmokeContent(onSnapshot: (RealWebViewSmokeSnapshot) -> Unit) {
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                RealWebViewSmokeHarness(onSnapshot = onSnapshot)
            }
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

    private fun RealWebViewSmokeSnapshot.diagnostic(): String =
        "loadingState=$loadingState, lastLoadedUrl=$lastLoadedUrl, pageTitle=$pageTitle, " +
            "errorsForCurrentRequest=$errorsForCurrentRequest, jsProbeResult=$jsProbeResult"
}
