package com.multiplatform.webview.request

import com.multiplatform.webview.request.internal.WebViewSchemeRequestCoordinator
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebViewSchemeConfigTest {
    @Test
    fun uppercaseSchemeIsNormalizedForMatching() =
        runTest {
            val coordinator =
                WebViewSchemeRequestCoordinator(
                    config(registration("App+Local")),
                    StandardTestDispatcher(testScheduler),
                )

            assertTrue(coordinator.handlesScheme("app+local"))
            assertTrue(coordinator.handlesScheme("APP+LOCAL"))
            coordinator.close()
        }

    @Test
    fun emptyRegistrationsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            WebViewSchemeConfig(registrations = emptyList())
        }
    }

    @Test
    fun malformedAndReservedSchemesAreRejected() {
        listOf(
            "1app",
            "app/path",
            "app_*",
            "http",
            "HTTPS",
            "file",
            "data",
            "javascript",
            "about",
            "blob",
            "ws",
            "wss",
            "content",
            "intent",
            "mailto",
            "tel",
        ).forEach { scheme ->
            assertFailsWith<IllegalArgumentException>(scheme) {
                config(registration(scheme))
            }
        }
    }

    @Test
    fun duplicateSchemesAreRejectedCaseInsensitively() {
        assertFailsWith<IllegalArgumentException> {
            config(registration("app"), registration("APP"))
        }
    }

    @Test
    fun schedulingBoundsAndTimeoutAreValidated() {
        listOf(0, 65).forEach { concurrency ->
            assertFailsWith<IllegalArgumentException> {
                WebViewSchemeConfig(
                    registrations = listOf(registration("app")),
                    maxConcurrentRequests = concurrency,
                    maxPendingRequests = 64,
                )
            }
        }
        listOf(0, 3, 1025).forEach { pending ->
            assertFailsWith<IllegalArgumentException> {
                WebViewSchemeConfig(
                    registrations = listOf(registration("app")),
                    maxConcurrentRequests = 4,
                    maxPendingRequests = pending,
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            config(registration("app", timeoutMillis = 0))
        }
    }

    private fun config(vararg registrations: WebViewSchemeRegistration) = WebViewSchemeConfig(registrations = registrations.toList())

    private fun registration(
        scheme: String,
        timeoutMillis: Long = 30_000,
    ) = WebViewSchemeRegistration(scheme, timeoutMillis) {
        WebViewSchemeResponse(mimeType = "text/plain")
    }
}
