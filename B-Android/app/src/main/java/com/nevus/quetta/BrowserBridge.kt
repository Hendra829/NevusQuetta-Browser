package com.nevus.quetta

import android.webkit.JavascriptInterface

class BrowserBridge(private val onMedia: (String) -> Unit) {
    @JavascriptInterface fun mediaFound(url: String) {
        if (url.startsWith("https://")) onMedia(url)
    }
}
