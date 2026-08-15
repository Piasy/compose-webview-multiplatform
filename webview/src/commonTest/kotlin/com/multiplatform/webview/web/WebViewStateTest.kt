package com.multiplatform.webview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewStateTest {
    @Test
    fun stateStartsWithProvidedContentAndInitializingLoadingState() {
        val content = WebContent.Data("<html></html>")

        val state = WebViewState(content)

        assertEquals(content, state.content)
        assertEquals(LoadingState.Initializing, state.loadingState)
        assertTrue(state.isLoading)
        assertNull(state.lastLoadedUrl)
        assertNull(state.pageTitle)
        assertTrue(state.errorsForCurrentRequest.isEmpty())
    }
}
