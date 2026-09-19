package com.nevusquetta.browser.engine

import android.webkit.WebChromeClient
import android.webkit.WebView
import com.nevusquetta.browser.viewmodel.BrowserViewModel

/**
 * Progress WebView bisa berubah puluhan kali per detik; ViewModel akan melakukan debounce sebelum
 * state diteruskan ke Compose agar progress bar tidak memicu render berlebihan.
 */
class NevusQuettaWebChromeClient(
    private val viewModel: BrowserViewModel,
) : WebChromeClient() {

    override fun onProgressChanged(
        view: WebView?,
        newProgress: Int,
    ) {
        super.onProgressChanged(view, newProgress)
        viewModel.onProgressChanged(newProgress)
    }
}
