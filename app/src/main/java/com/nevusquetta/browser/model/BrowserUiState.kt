package com.nevusquetta.browser.model

/**
 * Single source of truth untuk tombol navigasi dan address bar.
 */
data class BrowserUiState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val currentUrl: String = DEFAULT_HOME_URL,
    val progress: Int = 0,
) {
    companion object {
        const val DEFAULT_HOME_URL: String = "https://www.example.com"
    }
}
