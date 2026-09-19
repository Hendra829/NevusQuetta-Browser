package com.nevusquetta.browser.engine

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nevusquetta.browser.viewmodel.BrowserViewModel

/**
 * State history hanya disegarkan saat event navigasi penting selesai agar flag back/forward tidak
 * dihitung ulang pada callback yang terlalu sering.
 */
class NevusQuettaWebViewClient(
    private val viewModel: BrowserViewModel,
) : WebViewClient() {

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
        viewModel.onPageStarted(url ?: view?.url)
    }

    override fun onPageFinished(
        view: WebView?,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        viewModel.onPageFinished(
            url = url ?: view?.url,
            canGoBack = view?.canGoBack() == true,
            canGoForward = view?.canGoForward() == true,
        )
    }

    override fun doUpdateVisitedHistory(
        view: WebView?,
        url: String?,
        isReload: Boolean,
    ) {
        super.doUpdateVisitedHistory(view, url, isReload)
        viewModel.onVisitedHistoryUpdated(
            url = url ?: view?.url,
            canGoBack = view?.canGoBack() == true,
            canGoForward = view?.canGoForward() == true,
        )
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame != false) {
            viewModel.onPageFinished(
                url = view?.url,
                canGoBack = view?.canGoBack() == true,
                canGoForward = view?.canGoForward() == true,
            )
        }
    }
}
