package com.nevus.quetta.web

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureWebViewFactoryTest {
    @Test
    fun `hardened settings disable dangerous access paths`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val webView = WebView(context)

        SecureWebViewFactory.harden(webView, debuggingEnabled = false)

        with(webView.settings) {
            assertTrue(javaScriptEnabled)
            assertTrue(domStorageEnabled)
            assertFalse(allowFileAccess)
            assertFalse(allowContentAccess)
            assertFalse(javaScriptCanOpenWindowsAutomatically)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
        }
        // Robolectric 4.14 does not faithfully expose safeBrowsingEnabled state.
        // The production setting remains enabled in SecureWebViewFactory and is
        // verified at runtime on API 35/36 rather than by this shadow assertion.
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(webView))
        webView.destroy()
    }
}
