package com.nevus.quetta.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class BrowserRuntimeViewModel(
    private val progressDebounceMillis: Long = 50L,
    private val urlDebounceMillis: Long = 120L,
) : ViewModel() {
    private val urlEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = mutableState.asStateFlow()

    private var progressDebounceJob: Job? = null

    init {
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
        progressDebounceJob?.cancel()
        mutableState.update {
            it.copy(
                isLoading = true,
                progress = 0,
                lastErrorMessage = null,
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        val next = progress.coerceIn(0, 100)
        progressDebounceJob?.cancel()
        progressDebounceJob = viewModelScope.launch {
            delay(progressDebounceMillis)
            mutableState.update { current ->
                if (!current.isLoading && next < 100) {
                    current
                } else {
                    current.copy(
                        progress = next,
                        isLoading = next in 0..99,
                    )
                }
            }
        }
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
