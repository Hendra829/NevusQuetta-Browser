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

    @Test
    fun plainHostGetsHttpsSchemeBeforeLoading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        val command = async(Dispatchers.Main) { viewModel.commands.first() }
        runCurrent()

        viewModel.submitAddress("example.com")
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(BrowserCommand.LoadUrl("https://example.com"), command.await())
        assertEquals("https://example.com", viewModel.uiState.value.currentUrl)
    }

    @Test
    fun invalidAddressFallsBackToHomeUrlBeforeLoading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        val command = async(Dispatchers.Main) { viewModel.commands.first() }
        runCurrent()

        viewModel.submitAddress("not a valid url")
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(BrowserCommand.LoadUrl(BrowserUiState.DEFAULT_HOME_URL), command.await())
        assertEquals(BrowserUiState.DEFAULT_HOME_URL, viewModel.uiState.value.currentUrl)
    }

    @Test
    fun lateProgressDoesNotRegressCompletedPage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        viewModel.onPageStarted("https://example.com")
        runCurrent()
        viewModel.onPageFinished("https://example.com", canGoBack = false, canGoForward = false)
        runCurrent()

        viewModel.onProgressChanged(35)
        advanceTimeBy(10L)
        runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(100, viewModel.uiState.value.progress)
    }

    @Test
    fun fullyQualifiedUrlIsPreservedBeforeLoading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        val command = async(Dispatchers.Main) { viewModel.commands.first() }
        runCurrent()

        viewModel.submitAddress("http://example.com/path")
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(BrowserCommand.LoadUrl("http://example.com/path"), command.await())
        assertEquals("http://example.com/path", viewModel.uiState.value.currentUrl)
    }

    @Test
    fun nonHttpSchemeIsPreservedBeforeLoading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        val command = async(Dispatchers.Main) { viewModel.commands.first() }
        runCurrent()

        viewModel.submitAddress("about:blank")
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(BrowserCommand.LoadUrl("about:blank"), command.await())
        assertEquals("about:blank", viewModel.uiState.value.currentUrl)
    }

    @Test
    fun latestSubmittedAddressWinsWhenRequestsOverlap() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = BrowserViewModel(
            urlNormalizationDispatcher = dispatcher,
            progressDebounceMillis = 10L,
            urlDebounceMillis = 10L,
        )

        val command = async(Dispatchers.Main) { viewModel.commands.first() }
        runCurrent()

        viewModel.submitAddress("first.example")
        viewModel.submitAddress("second.example")
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(BrowserCommand.LoadUrl("https://second.example"), command.await())
        assertEquals("https://second.example", viewModel.uiState.value.currentUrl)
    }
}
