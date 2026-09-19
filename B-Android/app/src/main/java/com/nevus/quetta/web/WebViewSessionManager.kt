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
            val profileName = PRIVATE_PROFILE_PREFIX + tab.id.replace("-", "")
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
        val profileName = privateProfiles.remove(tabId)

        if (profileName != null) {
            purgePrivateProfileData(webView)
        }

        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.onPause()
        webView.clearHistory()
        webView.clearFormData()
        webView.destroy()

        if (profileName != null) {
            schedulePrivateProfileDeletion(profileName)
        }
    }

    private fun purgePrivateProfileData(webView: WebView) {
        if (!supportsPrivateProfiles()) return
        runCatching {
            val profile = WebViewCompat.getProfile(webView)
            profile.webStorage.deleteAllData()
            profile.geolocationPermissions.clearAll()
            val cookies = profile.cookieManager
            cookies.removeAllCookies {
                cookies.flush()
            }
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
            val deletion = runCatching {
                ProfileStore.getInstance().deleteProfile(profileName)
            }

            // true  = profile existed and deletion was accepted.
            // false = profile no longer exists. Both are terminal success states.
            if (deletion.isSuccess) return@postDelayed

            if (attempt < DELETE_RETRY_DELAYS_MS.lastIndex) {
                schedulePrivateProfileDeletion(profileName, attempt + 1)
            } else {
                context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(LAST_PRIVATE_DELETE_FAILURE, profileName)
                    .apply()
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
        const val PRIVATE_PROFILE_PREFIX = "nevus_private_"
        const val RUNTIME_PREFS = "nevus_runtime"
        const val LAST_PRIVATE_DELETE_FAILURE = "lastPrivateProfileDeleteFailure"
        val DELETE_RETRY_DELAYS_MS = longArrayOf(100L, 250L, 500L, 1000L, 2000L, 3000L)
    }
}
