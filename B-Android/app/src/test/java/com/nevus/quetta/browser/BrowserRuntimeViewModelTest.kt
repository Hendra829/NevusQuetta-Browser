package com.nevus.quetta.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserRuntimeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun pageLifecycleUpdatesSingleUiState() = runTest {
        val viewModel = BrowserRuntimeViewModel(
            progressDebounceMillis = 10,
            urlDebounceMillis = 10,
        )

        viewModel.onPageStarted("https://example.com/")
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(0, viewModel.uiState.value.progress)

        viewModel.onVisitedHistoryUpdated(
            url = "https://example.com/",
            canGoBack = true,
            canGoForward = false,
        )
        advanceTimeBy(10)
        runCurrent()

        viewModel.onPageFinished(
            url = "https://example.com/",
            canGoBack = true,
            canGoForward = false,
        )
        advanceTimeBy(10)
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(100, state.progress)
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
        assertEquals("https://example.com/", state.currentUrl)
    }

    @Test
    fun progressIsDebouncedAndLateProgressCannotRegressFinishedPage() = runTest {
        val viewModel = BrowserRuntimeViewModel(
            progressDebounceMillis = 25,
            urlDebounceMillis = 10,
        )

        viewModel.onPageStarted("https://example.com/")
        viewModel.onProgressChanged(10)
        viewModel.onProgressChanged(45)
        advanceTimeBy(24)
        assertEquals(0, viewModel.uiState.value.progress)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(45, viewModel.uiState.value.progress)

        viewModel.onPageFinished(
            url = "https://example.com/",
            canGoBack = false,
            canGoForward = false,
        )
        advanceTimeBy(25)
        runCurrent()
        assertEquals(100, viewModel.uiState.value.progress)

        // Progress must be emitted *after* the page finished and *after* the debounce
        // window elapsed, otherwise the still-arming debounce fires post-finish and
        // legitimately overwrites 100 (progressEvents is a buffered SharedFlow, so
        // every write issued before the virtual clock advances folds into one
        // debounced emission). Verifying the guard requires an actually-late event.
        viewModel.onProgressChanged(30)
        advanceTimeBy(25)
        runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(100, viewModel.uiState.value.progress)
    }

    @Test
    fun syncActiveWebViewUpdatesNavigationWithoutStartingLoad() = runTest {
        val viewModel = BrowserRuntimeViewModel()
        viewModel.syncActiveWebView(
            url = "https://developer.android.com/",
            canGoBack = true,
            canGoForward = true,
        )

        val state = viewModel.uiState.value
        assertEquals("https://developer.android.com/", state.currentUrl)
        assertTrue(state.canGoBack)
        assertTrue(state.canGoForward)
        assertFalse(state.isLoading)
    }
}
