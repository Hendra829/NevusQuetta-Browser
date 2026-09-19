package com.nevus.quetta.web

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())

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

        privateProfiles.remove(tabId)?.let { profileName ->
            schedulePrivateProfileDeletion(profileName)
        }
    }

    private fun schedulePrivateProfileDeletion(
        profileName: String,
        attempt: Int = 0,
    ) {
        if (!supportsPrivateProfiles()) return

        val delayMillis = DELETE_RETRY_DELAYS_MS[
            attempt.coerceAtMost(DELETE_RETRY_DELAYS_MS.lastIndex)
        ]

        mainHandler.postDelayed({
            val store = ProfileStore.getInstance()
            val names = runCatching { store.getAllProfileNames() }.getOrElse { emptyList() }
            if (profileName !in names) return@postDelayed

            val result = runCatching {
                store.deleteProfile(profileName)
            }

            if (result.isSuccess && result.getOrDefault(false)) {
                return@postDelayed
            }

            if (attempt < DELETE_RETRY_DELAYS_MS.lastIndex) {
                schedulePrivateProfileDeletion(profileName, attempt + 1)
            }
        }, delayMillis)
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

    private companion object {
        val DELETE_RETRY_DELAYS_MS = longArrayOf(100L, 250L, 500L, 1000L, 2000L, 3000L)
    }
}
