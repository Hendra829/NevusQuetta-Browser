package com.nevus.quetta.web

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nevus.quetta.tabs.BrowserTab
import com.nevus.quetta.tabs.TabState

class PrivateProfileUnsupportedException :
    IllegalStateException("WebView MULTI_PROFILE tidak didukung")

class WebViewSessionManager(
    private val context: Context,
) {
    private val sessions = LinkedHashMap<String, WebView>()
    private val privateProfiles = mutableMapOf<String, String>()

    fun supportsPrivateProfiles(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun obtain(
        tab: BrowserTab,
        configure: (WebView) -> Unit,
    ): WebView {
        sessions[tab.id]?.let { return it }

        val webView = WebView(context)
        if (tab.isPrivate) {
            if (!supportsPrivateProfiles()) {
                webView.destroy()
                throw PrivateProfileUnsupportedException()
            }
            val profileName = "nevus_private_" + tab.id.replace("-", "")
            WebViewCompat.setProfile(webView, profileName)
            privateProfiles[tab.id] = profileName
        }

        configure(webView)
        sessions[tab.id] = webView
        return webView
    }

    fun existing(tabId: String): WebView? = sessions[tabId]

    fun release(tabId: String) {
        val webView = sessions.remove(tabId) ?: return
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.onPause()
        webView.destroy()

        val profileName = privateProfiles.remove(tabId)
        if (profileName != null && supportsPrivateProfiles()) {
            runCatching { ProfileStore.getInstance().deleteProfile(profileName) }
        }
    }

    fun trimInactive(state: TabState, maxResident: Int = 4) {
        if (sessions.size <= maxResident) return
        val inactive = state.tabs
            .filter { it.id != state.activeTabId && sessions.containsKey(it.id) }
            .sortedBy { it.lastAccessedAt }

        inactive.forEach { tab ->
            if (sessions.size <= maxResident) return
            release(tab.id)
        }
    }

    fun pauseAll() {
        sessions.values.forEach {
            it.onPause()
            it.pauseTimers()
        }
    }

    fun resumeActive(activeTabId: String) {
        sessions[activeTabId]?.let {
            it.onResume()
            it.resumeTimers()
        }
    }

    fun destroyAll() {
        sessions.keys.toList().forEach(::release)
    }
}
