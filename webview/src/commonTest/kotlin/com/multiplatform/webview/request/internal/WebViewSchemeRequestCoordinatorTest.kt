@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.multiplatform.webview.request.internal

import com.multiplatform.webview.request.WebViewSchemeConfig
import com.multiplatform.webview.request.WebViewSchemeHandler
import com.multiplatform.webview.request.WebViewSchemeObserver
import com.multiplatform.webview.request.WebViewSchemeOutcome
import com.multiplatform.webview.request.WebViewSchemeRegistration
import com.multiplatform.webview.request.WebViewSchemeRequest
import com.multiplatform.webview.request.WebViewSchemeRequestContext
import com.multiplatform.webview.request.WebViewSchemeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebViewSchemeRequestCoordinatorTest {
    @Test
    fun successfulResponseIsNormalizedWithoutCopyingBody() =
        runTest {
            val body = byteArrayOf(1, 2, 3)
            val coordinator =
                coordinator {
                    WebViewSchemeResponse(
                        body = body,
                        mimeType = "application/octet-stream",
                        encoding = "binary",
                        statusCode = 201,
                        headers = mapOf("X-Result" to "created"),
                    )
                }

            val result = async { coordinator.execute(request()) }
            runCurrent()

            with(result.await()) {
                assertSame(body, this.body)
                assertEquals(201, statusCode)
                assertEquals("Created", reasonPhrase)
                assertEquals("created", headers["X-Result"])
                assertEquals("application/octet-stream; charset=binary", headers["Content-Type"])
                assertEquals("3", headers["Content-Length"])
            }
            coordinator.close()
        }

    @Test
    fun headRunsHandlerButSuppressesBodyAndPreservesOriginalLength() =
        runTest {
            var handledMethod: String? = null
            val coordinator =
                coordinator { request ->
                    handledMethod = request.method
                    WebViewSchemeResponse(body = byteArrayOf(1, 2, 3, 4), mimeType = "text/plain")
                }

            val result = async { coordinator.execute(request(method = "head")) }
            runCurrent()

            assertEquals("HEAD", handledMethod)
            assertTrue(result.await().body.isEmpty())
            assertEquals("4", result.await().headers["Content-Length"])
            coordinator.close()
        }

    @Test
    fun noContentStatusForcesEmptyBodyAndZeroLength() =
        runTest {
            val coordinator =
                coordinator {
                    WebViewSchemeResponse(body = byteArrayOf(1), mimeType = "text/plain", statusCode = 204)
                }

            val result = async { coordinator.execute(request()) }
            runCurrent()

            assertTrue(result.await().body.isEmpty())
            assertEquals("0", result.await().headers["Content-Length"])
            coordinator.close()
        }

    @Test
    fun unsupportedMethodReturns405WithoutStartingHandler() =
        runTest {
            var handlerStarted = false
            val observer = RecordingObserver()
            val coordinator =
                coordinator(observer = observer) {
                    handlerStarted = true
                    WebViewSchemeResponse(mimeType = "text/plain")
                }

            val result = async { coordinator.execute(request(method = "post")) }
            runCurrent()

            assertEquals(405, result.await().statusCode)
            assertEquals("GET, HEAD", result.await().headers["Allow"])
            assertEquals("Method Not Allowed", result.await().body.decodeToString())
            assertTrue(!handlerStarted)
            assertEquals(listOf("received:POST", "completed:UnsupportedMethod"), observer.events)
            coordinator.close()
        }

    @Test
    fun handlerExceptionReturnsSanitized500AndReportsOriginalThrowable() =
        runTest {
            val failure = IllegalStateException("secret failure")
            val observer = RecordingObserver()
            val coordinator = coordinator(observer = observer) { throw failure }

            val result = async { coordinator.execute(request()) }
            runCurrent()

            assertEquals(500, result.await().statusCode)
            assertEquals("Internal Server Error", result.await().body.decodeToString())
            assertTrue("secret" !in result.await().body.decodeToString())
            val outcome = assertIs<WebViewSchemeOutcome.HandlerException>(observer.outcomes.single())
            assertSame(failure, outcome.throwable)
            coordinator.close()
        }

    @Test
    fun illegalArgumentThrownByHandlerIsStillAHandlerException() =
        runTest {
            val failure = IllegalArgumentException("handler rejected input")
            val observer = RecordingObserver()
            val coordinator = coordinator(observer = observer) { throw failure }

            val result = async { coordinator.execute(request()) }
            runCurrent()

            assertEquals(500, result.await().statusCode)
            val outcome = assertIs<WebViewSchemeOutcome.HandlerException>(observer.outcomes.single())
            assertSame(failure, outcome.throwable)
            coordinator.close()
        }

    @Test
    fun cancellationExceptionThrownByActiveHandlerIsAHandlerException() =
        runTest {
            val failure = CancellationException("handler chose to cancel")
            val observer = RecordingObserver()
            val coordinator = coordinator(observer = observer) { throw failure }

            val result = async { coordinator.execute(request()) }
            runCurrent()

            assertEquals(500, result.await().statusCode)
            val outcome = assertIs<WebViewSchemeOutcome.HandlerException>(observer.outcomes.single())
            assertSame(failure, outcome.throwable)
            coordinator.close()
        }

    @Test
    fun invalidResponseReturns500AndReportsValidationFailure() =
        runTest {
            val observer = RecordingObserver()
            val coordinator =
                coordinator(observer = observer) {
                    WebViewSchemeResponse(
                        mimeType = "text/plain",
                        statusCode = 302,
                        headers = mapOf("Content-Length" to "9"),
                    )
                }

            val result = async { coordinator.execute(request()) }
            runCurrent()

            assertEquals(500, result.await().statusCode)
            assertIs<WebViewSchemeOutcome.InvalidResponse>(observer.outcomes.single())
            coordinator.close()
        }

    @Test
    fun queueIsStrictFifoAndNeverExceedsConcurrency() =
        runTest {
            val gates = (1..3).associateWith { CompletableDeferred<Unit>() }
            val started = mutableListOf<Int>()
            var active = 0
            var maximumActive = 0
            val coordinator =
                coordinator(maxConcurrentRequests = 1, maxPendingRequests = 3) { request ->
                    val id = request.url.substringAfterLast('/').toInt()
                    started += id
                    active += 1
                    maximumActive = maxOf(maximumActive, active)
                    gates.getValue(id).await()
                    active -= 1
                    WebViewSchemeResponse(mimeType = "text/plain")
                }
            val results = (1..3).map { id -> async { coordinator.execute(request(url = "app://host/$id")) } }
            runCurrent()

            assertEquals(listOf(1), started)
            gates.getValue(1).complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2), started)
            gates.getValue(2).complete(Unit)
            runCurrent()
            assertEquals(listOf(1, 2, 3), started)
            gates.getValue(3).complete(Unit)
            runCurrent()
            results.forEach { assertEquals(200, it.await().statusCode) }
            assertEquals(1, maximumActive)
            coordinator.close()
        }

    @Test
    fun defaultConcurrencyStartsFourRequestsButNotTheFifth() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var starts = 0
            val coordinator =
                coordinator {
                    starts += 1
                    gate.await()
                    WebViewSchemeResponse(mimeType = "text/plain")
                }
            val results =
                (1..5).map { id ->
                    async { coordinator.execute(request(url = "app://host/$id")) }
                }
            runCurrent()

            assertEquals(4, starts)
            gate.complete(Unit)
            runCurrent()
            results.forEach { assertEquals(200, it.await().statusCode) }
            assertEquals(5, starts)
            coordinator.close()
        }

    @Test
    fun handlerReceivesOriginalUrlQueryAndHeaders() =
        runTest {
            var handledRequest: WebViewSchemeRequest? = null
            val coordinator =
                coordinator { request ->
                    handledRequest = request
                    WebViewSchemeResponse(mimeType = "text/plain")
                }
            val request =
                WebViewSchemeRequest(
                    url = "app://host/path?one=1&two=hello%20world",
                    method = "get",
                    headers = mapOf("X-Request" to "complete"),
                )

            val result = async { coordinator.execute(request) }
            runCurrent()

            assertEquals(200, result.await().statusCode)
            assertEquals(request.url, handledRequest?.url)
            assertEquals("GET", handledRequest?.method)
            assertEquals(mapOf("X-Request" to "complete"), handledRequest?.headers)
            coordinator.close()
        }

    @Test
    fun fullPendingBudgetReturns503WithoutStartingHandler() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var starts = 0
            val observer = RecordingObserver()
            val coordinator =
                coordinator(maxConcurrentRequests = 1, maxPendingRequests = 1, observer = observer) {
                    starts += 1
                    gate.await()
                    WebViewSchemeResponse(mimeType = "text/plain")
                }
            val first = async { coordinator.execute(request(url = "app://host/first")) }
            runCurrent()
            val rejected = async { coordinator.execute(request(url = "app://host/rejected")) }
            runCurrent()

            assertEquals(503, rejected.await().statusCode)
            assertEquals(1, starts)
            assertTrue(observer.outcomes.any { it is WebViewSchemeOutcome.QueueFull })
            gate.complete(Unit)
            runCurrent()
            first.await()
            coordinator.close()
        }

    @Test
    fun timeoutIncludesTimeSpentWaitingInQueue() =
        runTest {
            val runningGate = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(
                    registrations =
                        listOf(
                            registration("app", timeoutMillis = 1_000) {
                                runningGate.await()
                                WebViewSchemeResponse(mimeType = "text/plain")
                            },
                            registration("quick", timeoutMillis = 100) {
                                WebViewSchemeResponse(mimeType = "text/plain")
                            },
                        ),
                    maxConcurrentRequests = 1,
                    maxPendingRequests = 2,
                )
            val running = async { coordinator.execute(request(url = "app://host/running")) }
            runCurrent()
            val queued = async { coordinator.execute(request(url = "quick://host/queued")) }
            runCurrent()
            advanceTimeBy(101)
            runCurrent()

            assertEquals(504, queued.await().statusCode)
            runningGate.complete(Unit)
            runCurrent()
            running.await()
            coordinator.close()
        }

    @Test
    fun timeoutCancelsRunningHandlerAndReturns504() =
        runTest {
            val cancelled = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(timeoutMillis = 100) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val result = async { coordinator.execute(request()) }
            runCurrent()
            advanceTimeBy(101)
            runCurrent()

            assertEquals(504, result.await().statusCode)
            assertTrue(cancelled.isCompleted)
            coordinator.close()
        }

    @Test
    fun callerCancellationReportsCancelledExactlyOnce() =
        runTest {
            val observer = RecordingObserver()
            val coordinator = coordinator(observer = observer) { awaitCancellation() }
            val requestJob = async { coordinator.execute(request()) }
            runCurrent()

            requestJob.cancelAndJoin()
            runCurrent()

            assertEquals(1, observer.outcomes.count { it is WebViewSchemeOutcome.Cancelled })
            coordinator.close()
        }

    @Test
    fun closeCancelsRunningAndQueuedRequestsAndUnblocksWaiters() =
        runTest {
            val observer = RecordingObserver()
            val coordinator =
                coordinator(maxConcurrentRequests = 1, maxPendingRequests = 2, observer = observer) {
                    awaitCancellation()
                }
            val running = async { coordinator.execute(request(url = "app://host/running")) }
            val queued = async { coordinator.execute(request(url = "app://host/queued")) }
            runCurrent()

            coordinator.close()
            runCurrent()

            assertEquals(503, running.await().statusCode)
            assertTrue(running.await().body.isEmpty())
            assertEquals(503, queued.await().statusCode)
            assertEquals(2, observer.outcomes.count { it is WebViewSchemeOutcome.Cancelled })
        }

    @Test
    fun observerReceivesOrderedLifecycleWithResponseMetadata() =
        runTest {
            val observer = RecordingObserver()
            val coordinator =
                coordinator(observer = observer) {
                    delay(10)
                    WebViewSchemeResponse(
                        body = byteArrayOf(1, 2),
                        mimeType = "text/css",
                        headers = mapOf("X-Test" to "yes"),
                    )
                }
            val result = async { coordinator.execute(request(url = "app://host/style.css?theme=dark")) }
            runCurrent()
            advanceTimeBy(10)
            runCurrent()
            result.await()

            assertEquals(listOf("received:GET", "started:GET", "completed:Response"), observer.events)
            val outcome = assertIs<WebViewSchemeOutcome.Response>(observer.outcomes.single())
            assertEquals(200, outcome.statusCode)
            assertEquals("text/css", outcome.mimeType)
            assertEquals(2, outcome.bodySize)
            assertEquals("yes", outcome.headers["X-Test"])
            assertEquals(1, observer.requestIds.distinct().size)
            assertTrue(observer.totalDurations.single() >= 0)
            assertTrue(observer.handlerDurations.single()!! >= 0)
            coordinator.close()
        }

    @Test
    fun responseValidationRejectsEveryForbiddenShape() =
        runTest {
            val invalidResponses =
                listOf(
                    WebViewSchemeResponse(mimeType = ""),
                    WebViewSchemeResponse(mimeType = "text/plain", statusCode = 199),
                    WebViewSchemeResponse(mimeType = "text/plain", statusCode = 300),
                    WebViewSchemeResponse(mimeType = "text/plain", statusCode = 600),
                    WebViewSchemeResponse(mimeType = "text/plain", reasonPhrase = ""),
                    WebViewSchemeResponse(mimeType = "text/plain", reasonPhrase = "bad\nreason"),
                    WebViewSchemeResponse(mimeType = "text/plain", headers = mapOf("X-Test\r" to "x")),
                    WebViewSchemeResponse(mimeType = "text/plain", headers = mapOf("X-Test" to "x\ny")),
                    WebViewSchemeResponse(mimeType = "text/plain", headers = mapOf("Content-Type" to "x")),
                    WebViewSchemeResponse(mimeType = "text/plain", headers = mapOf("content-length" to "1")),
                    WebViewSchemeResponse(mimeType = "text/plain", headers = mapOf("X-Test" to "x", "x-test" to "y")),
                )

            invalidResponses.forEach { response ->
                val observer = RecordingObserver()
                val coordinator = coordinator(observer = observer) { response }
                val result = async { coordinator.execute(request()) }
                runCurrent()
                assertEquals(500, result.await().statusCode)
                assertIs<WebViewSchemeOutcome.InvalidResponse>(observer.outcomes.single())
                coordinator.close()
            }
        }

    private fun TestScope.coordinator(
        timeoutMillis: Long = 30_000,
        maxConcurrentRequests: Int = 4,
        maxPendingRequests: Int = 64,
        observer: WebViewSchemeObserver? = null,
        handler: WebViewSchemeHandler,
    ) = coordinator(
        registrations = listOf(registration("app", timeoutMillis, handler)),
        maxConcurrentRequests = maxConcurrentRequests,
        maxPendingRequests = maxPendingRequests,
        observer = observer,
    )

    private fun TestScope.coordinator(
        registrations: List<WebViewSchemeRegistration>,
        maxConcurrentRequests: Int,
        maxPendingRequests: Int,
        observer: WebViewSchemeObserver? = null,
    ) = WebViewSchemeRequestCoordinator(
        WebViewSchemeConfig(
            registrations = registrations,
            maxConcurrentRequests = maxConcurrentRequests,
            maxPendingRequests = maxPendingRequests,
            observer = observer,
        ),
        StandardTestDispatcher(testScheduler),
    )

    private fun registration(
        scheme: String,
        timeoutMillis: Long,
        handler: WebViewSchemeHandler,
    ) = WebViewSchemeRegistration(scheme, timeoutMillis, handler)

    private fun request(
        url: String = "app://host/index.html?name=value",
        method: String = "GET",
    ) = WebViewSchemeRequest(url, method, mapOf("X-Request" to "yes"))

    private class RecordingObserver : WebViewSchemeObserver {
        val events = mutableListOf<String>()
        val outcomes = mutableListOf<WebViewSchemeOutcome>()
        val requestIds = mutableListOf<Long>()
        val totalDurations = mutableListOf<Long>()
        val handlerDurations = mutableListOf<Long?>()

        override fun onRequestReceived(context: WebViewSchemeRequestContext) {
            events += "received:${context.request.method}"
            requestIds += context.requestId
        }

        override fun onHandlerStarted(context: WebViewSchemeRequestContext) {
            events += "started:${context.request.method}"
            requestIds += context.requestId
        }

        override fun onRequestCompleted(
            context: WebViewSchemeRequestContext,
            outcome: WebViewSchemeOutcome,
            totalDurationMillis: Long,
            handlerDurationMillis: Long?,
        ) {
            events += "completed:${outcome::class.simpleName}"
            outcomes += outcome
            requestIds += context.requestId
            totalDurations += totalDurationMillis
            handlerDurations += handlerDurationMillis
        }
    }
}
