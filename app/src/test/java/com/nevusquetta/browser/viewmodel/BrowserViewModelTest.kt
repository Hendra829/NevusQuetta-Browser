package com.nevusquetta.browser.viewmodel

import com.nevusquetta.browser.model.BrowserUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
class BrowserViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun pageLifecycleUpdatesLoadingAndHistoryState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        viewModel.onPageStarted("https://developer.android.com")
        runCurrent()
        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(0, viewModel.uiState.value.progress)

        viewModel.onVisitedHistoryUpdated(
            url = "https://developer.android.com",
            canGoBack = true,
            canGoForward = false,
        )
        runCurrent()
        assertTrue(viewModel.uiState.value.canGoBack)
        assertFalse(viewModel.uiState.value.canGoForward)

        viewModel.onPageFinished(
            url = "https://developer.android.com",
            canGoBack = true,
            canGoForward = false,
        )
        advanceTimeBy(10L)
        runCurrent()

        val finalState = viewModel.uiState.value
        assertFalse(finalState.isLoading)
        assertEquals(100, finalState.progress)
        assertEquals("https://developer.android.com", finalState.currentUrl)
    }

    @Test
    fun progressAndUrlUpdatesAreDebouncedAndDistinct() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 25L,
            urlDebounceMillis = 25L,
        )

        viewModel.onProgressChanged(10)
        viewModel.onProgressChanged(10)
        viewModel.onProgressChanged(12)
        viewModel.onProgressChanged(12)
        advanceTimeBy(24L)
        assertEquals(0, viewModel.uiState.value.progress)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(12, viewModel.uiState.value.progress)

        val nextDistinctUrl = async(Dispatchers.Main) { viewModel.uiState.drop(1).first() }
        viewModel.onVisitedHistoryUpdated("https://example.org", false, false)
        viewModel.onVisitedHistoryUpdated("https://example.org", false, false)
        advanceTimeBy(25L)
        runCurrent()

        assertEquals("https://example.org", viewModel.uiState.value.currentUrl)
        assertEquals("https://example.org", nextDistinctUrl.await().currentUrl)
    }

    @Test
    fun blankAddressFallsBackToHomeUrl() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        viewModel.submitAddress("   ")
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(BrowserUiState.DEFAULT_HOME_URL, viewModel.uiState.value.currentUrl)
    }
}
