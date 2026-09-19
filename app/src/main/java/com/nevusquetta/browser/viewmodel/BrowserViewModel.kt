package com.nevusquetta.browser.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevusquetta.browser.model.BrowserUiState
import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
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
    private var hasStartedInitialNavigation = false
    private val addressSubmissions = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val progressEvents = MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val urlEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val commandChannel = Channel<BrowserCommand>(capacity = Channel.BUFFERED)
    private val _uiState = MutableStateFlow(BrowserUiState())

    val commands = commandChannel.receiveAsFlow()
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

        viewModelScope.launch {
            addressSubmissions.collectLatest { input ->
                val normalizedUrl = withContext(urlNormalizationDispatcher) {
                    normalizeUrl(input)
                }
                publishNavigation(normalizedUrl)
            }
        }

    }

    /**
     * Memulai load awal setelah collector command aktif agar navigasi pertama tidak hilang.
     */
    fun onCommandConsumerReady() {
        if (hasStartedInitialNavigation) {
            return
        }
        hasStartedInitialNavigation = true
        startNavigation(BrowserUiState.DEFAULT_HOME_URL)
    }

    /**
     * Menandai awal navigasi baru dan membersihkan error lama sebelum progress WebView berjalan.
     */
    fun onPageStarted(url: String?) {
        emitUrl(url)
        _uiState.update { current ->
            current.copy(
                isLoading = true,
                lastErrorMessage = null,
                progress = 0,
            )
        }
    }

    /**
     * Menyelesaikan state loading dan menyegarkan cache tombol back/forward saat halaman selesai.
     */
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
                lastErrorMessage = null,
                progress = 100,
            )
        }
    }

    /**
     * Menyegarkan cache histori ketika WebView mengubah visited history.
     */
    fun onVisitedHistoryUpdated(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        emitUrl(url)
        updateNavigationState(canGoBack = canGoBack, canGoForward = canGoForward)
    }

    /**
     * Menerima progress mentah WebView yang nanti akan didebounce sebelum masuk ke UI state.
     */
    fun onProgressChanged(progress: Int) {
        progressEvents.tryEmit(progress)
    }

    /**
     * Dipanggil saat navigasi frame utama gagal agar UI bisa menampilkan alasan kegagalan.
     */
    fun onPageFailed(
        url: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
        description: String?,
    ) {
        emitUrl(url)
        updateNavigationState(canGoBack = canGoBack, canGoForward = canGoForward)
        _uiState.update { current ->
            current.copy(
                isLoading = false,
                lastErrorMessage = description?.takeIf(String::isNotBlank),
                progress = 100,
            )
        }
    }

    /**
     * Meminta navigasi mundur dari sumber state yang sama dengan tombol back device.
     *
     * @return `true` bila WebView history tersedia dan command back dikirim.
     */
    fun requestBackNavigation(): Boolean {
        if (!_uiState.value.canGoBack) {
            return false
        }

        dispatchCommand(BrowserCommand.Back)
        return true
    }

    /**
     * Mengirim command maju hanya bila cache histori menyatakan aksi ini valid.
     */
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

    /**
     * Memulai navigasi ke halaman home default dan langsung memberi feedback loading ke UI.
     */
    fun onHomeClicked() {
        startNavigation(BrowserUiState.DEFAULT_HOME_URL)
    }

    /**
     * Menormalisasi input omnibox secara asynchronous lalu hanya mengizinkan submit terbaru yang
     * boleh mengirim command navigasi ke WebView.
     */
    fun submitAddress(input: String) {
        markNavigationStarted()
        addressSubmissions.tryEmit(input)
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
            commandChannel.send(command)
        }
    }

    private fun markNavigationStarted() {
        _uiState.update { current ->
            current.copy(
                isLoading = true,
                lastErrorMessage = null,
                progress = 0,
            )
        }
    }

    private fun startNavigation(url: String) {
        markNavigationStarted()
        viewModelScope.launch {
            publishNavigation(url)
        }
    }

    private suspend fun publishNavigation(url: String) {
        commandChannel.send(BrowserCommand.LoadUrl(url))
        emitUrl(url)
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

        val hasScheme = SCHEME_PATTERN.matches(trimmed)
        val candidate = if (hasScheme) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return try {
            val uri = URI(candidate).normalize()
            val normalizedScheme = uri.scheme?.lowercase()

            if (normalizedScheme == "about") {
                uri.toString()
            } else if (normalizedScheme in NETWORK_SCHEMES) {
                canonicalizeNetworkUrl(candidate)
            } else {
                BrowserUiState.DEFAULT_HOME_URL
            }
        } catch (_: URISyntaxException) {
            BrowserUiState.DEFAULT_HOME_URL
        }
    }

    private fun canonicalizeNetworkUrl(candidate: String): String {
        return try {
            val url = URL(candidate)
            val uri = url.toURI().normalize()
            val host = uri.host?.takeIf(String::isNotBlank)?.let {
                IDN.toASCII(it, IDN.USE_STD3_ASCII_RULES)
            }
                ?: return BrowserUiState.DEFAULT_HOME_URL
            if (!uri.userInfo.isNullOrBlank()) {
                return BrowserUiState.DEFAULT_HOME_URL
            }

            val normalizedPort = when {
                url.port == -1 -> -1
                url.protocol.equals("http", ignoreCase = true) && url.port == 80 -> -1
                url.protocol.equals("https", ignoreCase = true) && url.port == 443 -> -1
                else -> url.port
            }
            val normalizedPath = uri.path.takeUnless(String::isNullOrBlank) ?: "/"
            URI(
                url.protocol.lowercase(),
                null,
                host,
                normalizedPort,
                normalizedPath,
                uri.rawQuery,
                uri.rawFragment,
            ).toASCIIString()
        } catch (_: Exception) {
            BrowserUiState.DEFAULT_HOME_URL
        }
    }

    companion object {
        private val NETWORK_SCHEMES = setOf("http", "https")
        private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z\\d+\\-.]*:.*")
    }
}

sealed interface BrowserCommand {
    data object Back : BrowserCommand
    data object Forward : BrowserCommand
    data class LoadUrl(val url: String) : BrowserCommand
    data object Reload : BrowserCommand
    data object StopLoading : BrowserCommand
}
