package com.nevus.quetta.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class BrowserRuntimeViewModel(
    private val progressDebounceMillis: Long = 50L,
    private val urlDebounceMillis: Long = 120L,
) : ViewModel() {
    private val progressEvents = MutableSharedFlow<Int>(
        // replay = 1 retains the latest progress value for a collector that has not subscribed
        // yet. WebView progress callbacks run on the main thread and can call
        // onProgressChanged() before the collectors started in `init` have been dispatched.
        // With replay = 0 and no subscribers, tryEmit() reports success while the value is
        // silently dropped, so the debounced progress never reached the UI state and
        // BrowserRuntimeViewModelTest.progressIsDebouncedAndLateProgressCannotRegressFinishedPage
        // observed progress = 0 instead of the debounced value.
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val urlEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            progressEvents
                .distinctUntilChanged()
                .debounce(progressDebounceMillis)
                .collect { raw ->
                    val progress = raw.coerceIn(0, 100)
                    mutableState.update { current ->
                        if (!current.isLoading && progress < 100) {
                            current
                        } else {
                            current.copy(
                                progress = progress,
                                isLoading = progress in 0..99,
                            )
                        }
                    }
                }
        }

        viewModelScope.launch {
            urlEvents
                .filter(String::isNotBlank)
                .distinctUntilChanged()
                .debounce(urlDebounceMillis)
                .collect { url ->
                    mutableState.update { current ->
                        if (current.currentUrl == url) current else current.copy(currentUrl = url)
                    }
                }
        }
    }

    fun onNavigationRequested(url: String) {
        val clean = url.trim()
        mutableState.update {
            it.copy(
                currentUrl = clean.ifBlank { it.currentUrl },
                isLoading = true,
                progress = 0,
                lastErrorMessage = null,
            )
        }
    }

    fun onPageStarted(url: String?) {
        emitUrl(url)
        mutableState.update {
            it.copy(
                isLoading = true,
                progress = 0,
                lastErrorMessage = null,
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        progressEvents.tryEmit(progress)
    }

    fun onVisitedHistoryUpdated(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        emitUrl(url)
        updateNavigation(canGoBack, canGoForward)
    }

    fun onPageFinished(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        emitUrl(url)
        mutableState.update {
            it.copy(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isLoading = false,
                progress = 100,
                lastErrorMessage = null,
            )
        }
    }

    fun onPageFailed(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
        description: String?,
    ) {
        emitUrl(url)
        mutableState.update {
            it.copy(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isLoading = false,
                lastErrorMessage = description?.takeIf(String::isNotBlank),
            )
        }
    }

    fun syncActiveWebView(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        mutableState.update {
            it.copy(
                currentUrl = url?.takeIf(String::isNotBlank) ?: it.currentUrl,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                lastErrorMessage = null,
            )
        }
    }

    private fun updateNavigation(canGoBack: Boolean, canGoForward: Boolean) {
        mutableState.update { current ->
            if (current.canGoBack == canGoBack && current.canGoForward == canGoForward) {
                current
            } else {
                current.copy(canGoBack = canGoBack, canGoForward = canGoForward)
            }
        }
    }

    private fun emitUrl(url: String?) {
        url?.trim()?.takeIf(String::isNotEmpty)?.let(urlEvents::tryEmit)
    }
}
