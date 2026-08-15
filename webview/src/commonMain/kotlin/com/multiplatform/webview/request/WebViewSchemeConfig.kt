package com.multiplatform.webview.request

data class WebViewSchemeRegistration(
    val scheme: String,
    val timeoutMillis: Long = 30_000,
    val handler: WebViewSchemeHandler,
)

data class WebViewSchemeConfig(
    val registrations: List<WebViewSchemeRegistration>,
    val maxConcurrentRequests: Int = 4,
    val maxPendingRequests: Int = 64,
    val observer: WebViewSchemeObserver? = null,
) {
    init {
        require(registrations.isNotEmpty()) { "At least one custom scheme registration is required" }
        require(maxConcurrentRequests in 1..64) {
            "maxConcurrentRequests must be between 1 and 64"
        }
        require(maxPendingRequests in maxConcurrentRequests..1024) {
            "maxPendingRequests must be between maxConcurrentRequests and 1024"
        }

        val normalizedSchemes = mutableSetOf<String>()
        registrations.forEach { registration ->
            val scheme = registration.scheme.toAsciiLowercase()
            require(SCHEME_REGEX.matches(scheme)) { "Invalid custom scheme: ${registration.scheme}" }
            require(scheme !in RESERVED_SCHEMES) { "Reserved scheme cannot be handled: $scheme" }
            require(normalizedSchemes.add(scheme)) { "Duplicate custom scheme: $scheme" }
            require(registration.timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        }
    }
}

internal fun String.toAsciiLowercase(): String =
    map { character ->
        if (character in 'A'..'Z') character + ('a' - 'A') else character
    }.joinToString(separator = "")

internal fun String.toAsciiUppercase(): String =
    map { character ->
        if (character in 'a'..'z') character - ('a' - 'A') else character
    }.joinToString(separator = "")

private val SCHEME_REGEX = Regex("^[a-z][a-z0-9+.-]*$")

private val RESERVED_SCHEMES =
    setOf(
        "http",
        "https",
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
    )
