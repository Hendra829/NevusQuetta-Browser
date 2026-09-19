package com.nevus.quetta.web

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nevus.quetta.browser.BrowserRuntimeViewModel

class NevusWebViewClient(
    private val uiState: BrowserRuntimeViewModel,
    private val isActive: () -> Boolean,
    private val onPageStartedCallback: (WebView, String?) -> Unit,
    private val shouldOverrideMainFrame: (WebView, WebResourceRequest) -> Boolean,
    private val interceptRequest: (WebView, WebResourceRequest) -> WebResourceResponse?,
    private val onPageFinishedCallback: (WebView, String) -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        onPageStartedCallback(view, url)
        if (isActive()) {
            uiState.onPageStarted(url ?: view.url)
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        if (!request.isForMainFrame) return false
        return shouldOverrideMainFrame(view, request)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = interceptRequest(view, request)

    override fun doUpdateVisitedHistory(
        view: WebView,
        url: String?,
        isReload: Boolean,
    ) {
        if (isActive()) {
            uiState.onVisitedHistoryUpdated(
                url = url ?: view.url,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
            )
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageFinishedCallback(view, url)
        if (isActive()) {
            uiState.onPageFinished(
                url = url,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
            )
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame && isActive()) {
            uiState.onPageFailed(
                url = view.url,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
                description = error.description?.toString(),
            )
        }
    }
}
