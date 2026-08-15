package com.multiplatform.webview

import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.kevinnzou.sample.integrationtest.REAL_WEB_VIEW_SMOKE_EXPECTED_PROBE
import com.kevinnzou.sample.integrationtest.REAL_WEB_VIEW_SMOKE_TITLE
import com.kevinnzou.sample.integrationtest.RealWebViewSmokeHarness
import com.kevinnzou.sample.integrationtest.RealWebViewSmokeSnapshot
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
