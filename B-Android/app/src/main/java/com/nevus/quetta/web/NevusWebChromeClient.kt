package com.nevus.quetta.web

import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.nevus.quetta.browser.BrowserRuntimeViewModel

class NevusWebChromeClient(
    private val uiState: BrowserRuntimeViewModel,
    private val isActive: () -> Boolean,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        if (isActive()) {
            uiState.onProgressChanged(newProgress)
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }
}
