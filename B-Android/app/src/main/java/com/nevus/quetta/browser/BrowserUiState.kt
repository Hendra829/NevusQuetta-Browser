package com.nevus.quetta.browser

data class BrowserUiState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val currentUrl: String = "",
    val lastErrorMessage: String? = null,
    val progress: Int = 0,
)
