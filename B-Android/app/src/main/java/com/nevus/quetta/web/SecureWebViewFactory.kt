package com.nevus.quetta.web

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object SecureWebViewFactory {
    @Suppress("SetJavaScriptEnabled", "DEPRECATION")
    fun harden(webView: WebView, debuggingEnabled: Boolean) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        WebView.setWebContentsDebuggingEnabled(debuggingEnabled)
    }
}
