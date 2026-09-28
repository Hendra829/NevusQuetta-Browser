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

    @Test
    fun progressOneHundredClearsLoadingAfterDebounce() = runTest {
        val viewModel = BrowserRuntimeViewModel(
            progressDebounceMillis = 25,
            urlDebounceMillis = 10,
        )

        viewModel.onPageStarted("https://example.com/")
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.onProgressChanged(100)
        advanceTimeBy(26)
        runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(100, viewModel.uiState.value.progress)
    }

    @Test
    fun rapidProgressBurstAppliesOnlyLastValueAfterDebounce() = runTest {
        val viewModel = BrowserRuntimeViewModel(
            progressDebounceMillis = 25,
            urlDebounceMillis = 10,
        )

        viewModel.onPageStarted("https://example.com/")
        for (value in listOf(5, 15, 30, 60, 80)) {
            viewModel.onProgressChanged(value)
        }

        advanceTimeBy(24)
        assertEquals(0, viewModel.uiState.value.progress)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(80, viewModel.uiState.value.progress)
        assertTrue(viewModel.uiState.value.isLoading)
    }
}
