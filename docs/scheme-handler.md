# Custom scheme handlers

The custom scheme API lets Android and iOS WebViews load a main document and its subresources from a common suspend handler. Each WebView owns an independent FIFO queue, concurrency budget, timeout scope, observer lifecycle, and request ID sequence.

```kotlin
val state = rememberWebViewState("myapp://content/index.html")
val schemeConfig = remember {
    WebViewSchemeConfig(
        registrations = listOf(
            WebViewSchemeRegistration("myapp") { request ->
                when (request.url) {
                    "myapp://content/index.html" -> WebViewSchemeResponse(
                        body = "<link rel='stylesheet' href='/style.css'>".encodeToByteArray(),
                        mimeType = "text/html",
                        encoding = "utf-8",
                    )
                    "myapp://content/style.css" -> WebViewSchemeResponse(
                        body = "body { color: green; }".encodeToByteArray(),
                        mimeType = "text/css",
                        encoding = "utf-8",
                    )
                    else -> WebViewSchemeResponse(
                        body = "Not Found".encodeToByteArray(),
                        mimeType = "text/plain",
                        encoding = "utf-8",
                        statusCode = 404,
                    )
                }
            },
        ),
    )
}

WebView(state = state, schemeConfig = schemeConfig)
```

## Request and response rules

- Schemes are ASCII-lowercased and must match `^[a-z][a-z0-9+.-]*$`. Built-in and special schemes such as HTTP(S), file, data, JavaScript, blob, WebSocket, content, intent, mail, and telephone schemes are rejected. Host or path matching is the handler's responsibility.
- Only GET and HEAD are accepted. Other methods receive `405 Method Not Allowed` with `Allow: GET, HEAD` without entering the queue or handler. HEAD invokes the handler but delivers no body.
- Responses may use 2xx, 4xx, or 5xx status codes. Redirects are unsupported. MIME type is required. Reason phrases must be printable ASCII. Header names and values must not contain CR/LF, and names are unique case-insensitively.
- Do not return `Content-Type` or `Content-Length`; the library derives both. Status 204 and 205 force an empty body. The library does not copy returned headers or `ByteArray`; do not mutate them after the handler returns.
- Handler exceptions and invalid responses become a sanitized 500 response. Queue saturation becomes 503 and total timeout becomes 504. The original exception is available only through the observer.

`maxConcurrentRequests` defaults to 4 and accepts 1 through 64. `maxPendingRequests` includes running and queued requests, defaults to 64, and accepts the concurrency value through 1024. Registration timeout defaults to 30 seconds and includes queue time. Handlers must use cancellable suspend APIs; blocking work cannot be forcibly stopped safely.

## Lifecycle and compatibility

- `WebViewSchemeConfig` is fixed when the native WebView is created. A changed value during recomposition is logged and ignored; wrap the WebView in Compose `key(config)` to recreate it. A config or handler can be shared, but coordinators are never shared between WebViews.
- The scheme-handler overload rejects a navigator with the older `RequestInterceptor`. Android also rejects a custom `PlatformWebViewParams.client`; a custom Chrome client remains supported. On iOS a custom factory must create `WKWebView` with `WebViewFactoryParam.config`, because that configuration contains the registered handlers.
- Desktop and Wasm throw `UnsupportedOperationException` when this overload is used. Normal HTTPS, unregistered schemes, and Android's asset-loader fallback keep their existing behavior.
- Observer callbacks expose the complete request URL, query, headers, response metadata, and original exception. They never include the response body. Each received request completes exactly once. Observer exceptions are isolated from request processing.

## Platform limitations

Android does not provide a reliable per-request stop callback from WebView. A running handler may therefore continue until it returns, times out, or the WebView is disposed. Chromium does not natively allow `fetch()` for custom schemes; the library installs a document-start bridge scoped to registered scheme origins and routes those GET/HEAD calls through the same coordinator. `javascript:`, `blob:`, and some built-in file or asset requests do not reach `shouldInterceptRequest`.

iOS maps each `WKURLSchemeTask` to one coordinator request. `stopURLSchemeTask` cancels it and suppresses every later WebKit callback. Releasing the WebView cancels all tasks without sending response, data, finish, or failure callbacks.

The feature does not support 3xx responses. Redirect inspection therefore reports only the initial URL. HTTP-like 404 and 500 responses are normal scheme responses and are not added to `WebViewState.errorsForCurrentRequest`; that list remains reserved for native WebView error callbacks.
