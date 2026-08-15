package com.multiplatform.webview.request.internal

import com.multiplatform.webview.request.WebViewSchemeConfig
import com.multiplatform.webview.request.WebViewSchemeOutcome
import com.multiplatform.webview.request.WebViewSchemeRegistration
import com.multiplatform.webview.request.WebViewSchemeRequest
import com.multiplatform.webview.request.WebViewSchemeRequestContext
import com.multiplatform.webview.request.WebViewSchemeResponse
import com.multiplatform.webview.request.toAsciiLowercase
import com.multiplatform.webview.request.toAsciiUppercase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class WebViewSchemeRequestCoordinator(
    private val config: WebViewSchemeConfig,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val registrations =
        config.registrations.associateBy { it.scheme.toAsciiLowercase() }
    private val queue = ArrayDeque<PendingRequest>()
    private val activeRequests = mutableMapOf<Long, PendingRequest>()
    private var nextRequestId = 0L
    private var runningRequests = 0
    private var closed = false

    fun handlesScheme(scheme: String): Boolean = registrations.containsKey(scheme.toAsciiLowercase())

    suspend fun execute(request: WebViewSchemeRequest): WebViewSchemeResponse {
        val normalizedRequest = request.copy(method = request.method.toAsciiUppercase())
        val pending = enqueue(normalizedRequest)
        return try {
            pending.result.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancelRequest(pending.id) }
            throw cancelled
        }
    }

    private suspend fun enqueue(request: WebViewSchemeRequest): PendingRequest =
        mutex.withLock {
            val registration =
                registrations[request.url.substringBefore(':').toAsciiLowercase()]
                    ?: error("No custom scheme registration for ${request.url}")
            val pending =
                PendingRequest(
                    id = ++nextRequestId,
                    request = request,
                    registration = registration,
                )
            safeObserve { config.observer?.onRequestReceived(pending.context) }

            when {
                request.method != "GET" && request.method != "HEAD" -> {
                    completeLocked(
                        pending = pending,
                        response =
                            syntheticSchemeResponse(
                                statusCode = 405,
                                body = "Method Not Allowed",
                                isHead = false,
                                extraHeaders = mapOf("Allow" to "GET, HEAD"),
                            ),
                        outcome = WebViewSchemeOutcome.UnsupportedMethod(request.method),
                    )
                }

                closed -> {
                    completeLocked(
                        pending,
                        disposedSchemeResponse(),
                        WebViewSchemeOutcome.Cancelled,
                    )
                }

                activeRequests.size >= config.maxPendingRequests -> {
                    completeLocked(
                        pending = pending,
                        response =
                            syntheticSchemeResponse(
                                statusCode = 503,
                                body = "Service Unavailable",
                                isHead = request.method == "HEAD",
                            ),
                        outcome = WebViewSchemeOutcome.QueueFull,
                    )
                }

                else -> {
                    activeRequests[pending.id] = pending
                    queue.addLast(pending)
                    pending.timeoutJob =
                        scope.launch {
                            delay(registration.timeoutMillis)
                            timeoutRequest(pending.id)
                        }
                    dispatchLocked()
                }
            }
            pending
        }

    private fun dispatchLocked() {
        while (runningRequests < config.maxConcurrentRequests && queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            if (pending.completed) continue
            runningRequests += 1
            pending.running = true
            pending.handlerStarted = TimeSource.Monotonic.markNow()
            safeObserve { config.observer?.onHandlerStarted(pending.context) }
            pending.handlerJob =
                scope.launch {
                    try {
                        val response = pending.registration.handler.handle(pending.request)
                        val normalizedResult =
                            runCatching {
                                validateSchemeResponse(
                                    response = response,
                                    isHead = pending.request.method == "HEAD",
                                )
                            }.getOrElse { throwable ->
                                finishRequest(
                                    id = pending.id,
                                    response =
                                        syntheticSchemeResponse(
                                            statusCode = 500,
                                            body = "Internal Server Error",
                                            isHead = pending.request.method == "HEAD",
                                        ),
                                    outcome = WebViewSchemeOutcome.InvalidResponse(throwable),
                                )
                                return@launch
                            }
                        val (normalized, outcome) = normalizedResult
                        finishRequest(pending.id, normalized, outcome)
                    } catch (cancelled: CancellationException) {
                        finishRequest(
                            id = pending.id,
                            response =
                                syntheticSchemeResponse(
                                    statusCode = 500,
                                    body = "Internal Server Error",
                                    isHead = pending.request.method == "HEAD",
                                ),
                            outcome = WebViewSchemeOutcome.HandlerException(cancelled),
                        )
                    } catch (throwable: Throwable) {
                        finishRequest(
                            id = pending.id,
                            response =
                                syntheticSchemeResponse(
                                    statusCode = 500,
                                    body = "Internal Server Error",
                                    isHead = pending.request.method == "HEAD",
                                ),
                            outcome = WebViewSchemeOutcome.HandlerException(throwable),
                        )
                    }
                }
        }
    }

    private suspend fun finishRequest(
        id: Long,
        response: WebViewSchemeResponse,
        outcome: WebViewSchemeOutcome,
    ) {
        mutex.withLock {
            val pending = activeRequests[id] ?: return
            completeLocked(pending, response, outcome)
            dispatchLocked()
        }
    }

    private suspend fun timeoutRequest(id: Long) {
        mutex.withLock {
            val pending = activeRequests[id] ?: return
            completeLocked(
                pending = pending,
                response =
                    syntheticSchemeResponse(
                        statusCode = 504,
                        body = "Gateway Timeout",
                        isHead = pending.request.method == "HEAD",
                    ),
                outcome = WebViewSchemeOutcome.Timeout,
            )
            pending.handlerJob?.cancel()
            dispatchLocked()
        }
    }

    private suspend fun cancelRequest(id: Long) {
        mutex.withLock {
            val pending = activeRequests[id] ?: return
            completeLocked(pending, disposedSchemeResponse(), WebViewSchemeOutcome.Cancelled)
            pending.handlerJob?.cancel()
            dispatchLocked()
        }
    }

    private fun completeLocked(
        pending: PendingRequest,
        response: WebViewSchemeResponse,
        outcome: WebViewSchemeOutcome,
    ) {
        if (pending.completed) return
        pending.completed = true
        activeRequests.remove(pending.id)
        queue.remove(pending)
        pending.timeoutJob?.cancel()
        if (pending.running) {
            runningRequests -= 1
        }
        val handlerDuration = pending.handlerStarted?.elapsedNow()?.inWholeMilliseconds
        safeObserve {
            config.observer?.onRequestCompleted(
                context = pending.context,
                outcome = outcome,
                totalDurationMillis = pending.received.elapsedNow().inWholeMilliseconds,
                handlerDurationMillis = handlerDuration,
            )
        }
        pending.result.complete(response)
    }

    fun close() {
        scope.launch {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                activeRequests.values.toList().forEach { pending ->
                    completeLocked(
                        pending,
                        disposedSchemeResponse(),
                        WebViewSchemeOutcome.Cancelled,
                    )
                    pending.handlerJob?.cancel()
                }
                queue.clear()
            }
            scope.cancel()
        }
    }

    private inline fun safeObserve(block: () -> Unit) {
        runCatching(block)
    }

    private class PendingRequest(
        val id: Long,
        val request: WebViewSchemeRequest,
        val registration: WebViewSchemeRegistration,
        val result: CompletableDeferred<WebViewSchemeResponse> = CompletableDeferred(),
        val received: TimeMark = TimeSource.Monotonic.markNow(),
    ) {
        val context = WebViewSchemeRequestContext(id, request)
        var handlerStarted: TimeMark? = null
        var timeoutJob: Job? = null
        var handlerJob: Job? = null
        var running = false
        var completed = false
    }
}
