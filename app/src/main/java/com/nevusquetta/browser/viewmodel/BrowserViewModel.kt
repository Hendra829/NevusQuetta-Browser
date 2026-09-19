package com.nevusquetta.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevusquetta.browser.model.BrowserUiState
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Menyimpan state navigasi secara terpusat agar UI dan callback WebView memakai sumber data yang sama.
 *
 * Debounce dipakai untuk progress dan URL supaya callback WebView yang sangat sering tidak memicu
 * recomposition berlebihan pada toolbar.
 */
@OptIn(FlowPreview::class)
class BrowserViewModel(
    private val urlNormalizationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val progressDebounceMillis: Long = 50L,
    private val urlDebounceMillis: Long = 150L,
) : ViewModel() {

    private val progressEvents = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val urlEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _commands = MutableSharedFlow<BrowserCommand>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _uiState = MutableStateFlow(BrowserUiState())

    val commands: SharedFlow<BrowserCommand> = _commands.asSharedFlow()
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            progressEvents
                .distinctUntilChanged()
                .debounce(progressDebounceMillis)
                .collect { progress ->
                    val safeProgress = progress.coerceIn(0, 100)
                    _uiState.update { current ->
                        if (!current.isLoading && safeProgress < current.progress) {
                            return@update current
                        }
                        val nextLoading = if (safeProgress in 1..99) true else current.isLoading
                        current.copy(
                            isLoading = nextLoading,
                            progress = safeProgress,
                        )
                    }
                }
        }

        viewModelScope.launch {
            urlEvents
                .filter(String::isNotBlank)
                .distinctUntilChanged()
                .debounce(urlDebounceMillis)
                .collect { url ->
                    _uiState.update { current ->
                        if (current.currentUrl == url) {
                            current
                        } else {
                            current.copy(currentUrl = url)
                        }
                    }
                }
        }
    }

    fun onPageStarted(url: String?) {
        emitUrl(url)
        _uiState.update { current ->
            current.copy(
                isLoading = true,
                progress = 0,
            )
        }
    }

    fun onPageFinished(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        emitUrl(url)
        updateNavigationState(canGoBack = canGoBack, canGoForward = canGoForward)
        _uiState.update { current ->
            current.copy(
                isLoading = false,
                progress = 100,
            )
        }
    }

    fun onVisitedHistoryUpdated(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        emitUrl(url)
        updateNavigationState(canGoBack = canGoBack, canGoForward = canGoForward)
    }

    fun onProgressChanged(progress: Int) {
        progressEvents.tryEmit(progress)
    }

    fun requestBackNavigation(): Boolean {
        if (!_uiState.value.canGoBack) {
            return false
        }

        dispatchCommand(BrowserCommand.Back)
        return true
    }

    fun onForwardClicked() {
        if (_uiState.value.canGoForward) {
            dispatchCommand(BrowserCommand.Forward)
        }
    }

    /**
     * Satu handler untuk reload/stop agar keputusan aksi selalu memakai state terbaru yang sama.
     */
    fun onReloadStopClicked() {
        val command = if (_uiState.value.isLoading) {
            BrowserCommand.StopLoading
        } else {
            BrowserCommand.Reload
        }
        dispatchCommand(command)
    }

    fun onHomeClicked() {
        emitUrl(BrowserUiState.DEFAULT_HOME_URL)
        dispatchCommand(BrowserCommand.LoadUrl(BrowserUiState.DEFAULT_HOME_URL))
    }

    fun submitAddress(input: String) {
        viewModelScope.launch(urlNormalizationDispatcher) {
            val normalizedUrl = normalizeUrl(input)
            emitUrl(normalizedUrl)
            _commands.emit(BrowserCommand.LoadUrl(normalizedUrl))
        }
    }

    private fun updateNavigationState(
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        _uiState.update { current ->
            if (
                current.canGoBack == canGoBack &&
                current.canGoForward == canGoForward
            ) {
                current
            } else {
                current.copy(
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                )
            }
        }
    }

    private fun dispatchCommand(command: BrowserCommand) {
        viewModelScope.launch {
            _commands.emit(command)
        }
    }

    private fun emitUrl(url: String?) {
        val value = url?.trim().orEmpty()
        if (value.isNotEmpty()) {
            urlEvents.tryEmit(value)
        }
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return BrowserUiState.DEFAULT_HOME_URL
        }

        val candidate = if (SCHEME_PATTERN.matches(trimmed)) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return try {
            val uri = URI(candidate)
            if (uri.host.isNullOrBlank()) {
                BrowserUiState.DEFAULT_HOME_URL
            } else {
                uri.toString()
            }
        } catch (_: URISyntaxException) {
            BrowserUiState.DEFAULT_HOME_URL
        }
    }

    companion object {
        private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z\\d+\\-.]*://.+")
    }
}

sealed interface BrowserCommand {
    data object Back : BrowserCommand
    data object Forward : BrowserCommand
    data class LoadUrl(val url: String) : BrowserCommand
    data object Reload : BrowserCommand
    data object StopLoading : BrowserCommand
}
