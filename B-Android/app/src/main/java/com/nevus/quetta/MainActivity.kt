package com.nevus.quetta

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nevus.quetta.browser.BrowserCoordinator
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.BrowserRepository
import com.nevus.quetta.data.DownloadEntity
import com.nevus.quetta.data.DownloadKinds
import com.nevus.quetta.data.DownloadStatuses
import com.nevus.quetta.databinding.ActivityMainBinding
import com.nevus.quetta.download.DownloadRepository
import com.nevus.quetta.download.HlsDownloadCoordinator
import com.nevus.quetta.download.ManagedDownloadCoordinator
import com.nevus.quetta.download.ManagedDownloadResult
import com.nevus.quetta.performance.RuntimePerformanceMetrics
import com.nevus.quetta.navigation.NavigationController
import com.nevus.quetta.navigation.NavigationTarget
import com.nevus.quetta.tabs.BrowserTab
import com.nevus.quetta.tabs.TabManager
import com.nevus.quetta.tabs.TabState
import com.nevus.quetta.web.MediaBridgeHost
import com.nevus.quetta.web.MediaCandidate
import com.nevus.quetta.web.PrivateProfileUnsupportedException
import com.nevus.quetta.web.SecureWebViewFactory
import com.nevus.quetta.web.WebViewSessionManager
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var coordinator: BrowserCoordinator
    private lateinit var sessions: WebViewSessionManager
    private lateinit var downloads: ManagedDownloadCoordinator
    private lateinit var hlsDownloads: HlsDownloadCoordinator
    private lateinit var metrics: RuntimePerformanceMetrics

    private val bridgeHosts = mutableMapOf<String, MediaBridgeHost>()
    private val pageStartedAt = mutableMapOf<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val startPage = "https://www.google.com/"
    private val prefs by lazy { getSharedPreferences("nevus", MODE_PRIVATE) }
    private val navigation = NavigationController()

    private var currentWebView: WebView? = null
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        val database = BrowserDatabase.get(this)
        val repository = BrowserRepository(database)
        val downloadRepository = DownloadRepository(database)
        coordinator = BrowserCoordinator(
            tabs = TabManager(startPage),
            repository = repository,
            homeUrl = startPage,
        )
        sessions = WebViewSessionManager(this)
        downloads = ManagedDownloadCoordinator(this, downloadRepository)
        hlsDownloads = HlsDownloadCoordinator(this, downloadRepository)
        metrics = RuntimePerformanceMetrics(this)

        configureActions()
        configureBackHandling()

        scope.launch {
            val fallback = intent?.dataString
                ?: prefs.getString("lastUrl", startPage)
            coordinator.restoreSession(fallback)
            coordinator.tabs.state.collectLatest(::renderState)
        }
        scope.launch {
            while (isActive) {
                runCatching { downloads.refreshAll() }
                delay(DOWNLOAD_REFRESH_MS)
            }
        }
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun configureActions() {
        binding.back.setOnClickListener {
            currentWebView?.takeIf(WebView::canGoBack)?.goBack()
        }
        binding.forward.setOnClickListener {
            currentWebView?.takeIf(WebView::canGoForward)?.goForward()
        }
        binding.home.setOnClickListener { navigate(startPage) }
        binding.reload.setOnClickListener { currentWebView?.reload() }
        binding.bookmark.setOnClickListener { addCurrentBookmark() }
        binding.tabs.setOnClickListener { showTabsDialog() }
        binding.menu.setOnClickListener { showMenu(it) }
        binding.errorState.setOnClickListener { currentWebView?.reload() }
        binding.address.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (go) navigate(binding.address.text.toString())
            go
        }
    }

    private fun configureBackHandling() {
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val webView = currentWebView
                when {
                    webView?.canGoBack() == true -> webView.goBack()
                    coordinator.tabs.state.value.tabs.size > 1 -> closeActiveTab()
                    else -> isEnabled = false
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    private fun renderState(state: TabState) {
        binding.tabs.text = state.tabs.size.toString()
        binding.privateIndicator.visibility =
            if (state.activeTab.isPrivate) View.VISIBLE else View.GONE
        showActiveTab(state.activeTab)
        updateBackCallback(state)
        trimInactiveSessions(state, maxResident = 4)
    }

    private fun showActiveTab(tab: BrowserTab) {
        val webView = try {
            sessions.obtain(tab) { created -> configureWebView(tab.id, created) }
        } catch (_: PrivateProfileUnsupportedException) {
            Toast.makeText(this, R.string.private_unsupported, Toast.LENGTH_LONG).show()
            scope.launch { coordinator.closeTab(tab.id) }
            return
        }

        if (currentWebView === webView && webView.parent === binding.webContainer) {
            metrics.recordWebViewReuse()
        } else {
            currentWebView?.onPause()
            (webView.parent as? ViewGroup)?.removeView(webView)
            binding.webContainer.removeAllViews()
            binding.webContainer.addView(
                webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            currentWebView = webView
            metrics.recordWebViewRebind()
        }
        binding.address.setText(webView.url ?: tab.url)
        binding.errorState.visibility = View.GONE
        updateNavigationButtons(webView)

        if (webView.url == null) {
            runCatching { Uri.parse(tab.url) }
                .getOrNull()
                ?.let { bridgeHosts[tab.id]?.prepareFor(it) }
            webView.loadUrl(tab.url)
        }

        webView.onResume()
    }

    private fun configureWebView(tabId: String, webView: WebView) {
        metrics.recordWebViewCreated()
        SecureWebViewFactory.harden(webView, debuggingEnabled = BuildConfig.DEBUG)
        val bridge = MediaBridgeHost(this, webView) { candidate ->
            runOnUiThread {
                if (coordinator.tabs.state.value.activeTabId == tabId) {
                    offerDownload(tabId, candidate)
                }
            }
        }
        bridgeHosts[tabId] = bridge

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (coordinator.tabs.state.value.activeTabId != tabId) return
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
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

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageStartedAt[tabId] = android.os.SystemClock.elapsedRealtime()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (!request.isForMainFrame) return false
                return when (request.url.scheme?.lowercase()) {
                    "https" -> {
                        bridge.prepareFor(request.url)
                        false
                    }
                    "http" -> {
                        navigateOn(tabId, request.url.toString())
                        true
                    }
                    else -> true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                return if (NativeGuard.isBlockedHost(request.url.host.orEmpty())) {
                    WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        403,
                        "Blocked",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                } else {
                    null
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageStartedAt.remove(tabId)?.let { started ->
                    metrics.recordPageLoad(android.os.SystemClock.elapsedRealtime() - started)
                }
                val tab = coordinator.tabs.tab(tabId) ?: return
                scope.launch {
                    coordinator.pageFinished(tabId, url, view.title)
                }
                if (!tab.isPrivate && url.startsWith("https://")) {
                    prefs.edit().putString("lastUrl", url).apply()
                }
                if (coordinator.tabs.state.value.activeTabId == tabId) {
                    binding.address.setText(url)
                    binding.errorState.visibility = View.GONE
                    updateNavigationButtons(view)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame &&
                    coordinator.tabs.state.value.activeTabId == tabId
                ) {
                    binding.errorState.visibility = View.VISIBLE
                }
            }
        }

        webView.setDownloadListener(DownloadListener {
                url,
                userAgent,
                contentDisposition,
                mimeType,
                _ ->
            scope.launch {
                handleDownloadResult(
                    enqueueMedia(
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        sourcePage = webView.url,
                    ),
                )
            }
        })
    }

    private fun navigate(raw: String) {
        val tabId = coordinator.tabs.state.value.activeTabId
        navigateOn(tabId, raw)
    }

    private fun navigateOn(tabId: String, raw: String) {
        when (val target = navigation.resolve(raw)) {
            is NavigationTarget.Rejected -> {
                Toast.makeText(this, target.reason, Toast.LENGTH_SHORT).show()
            }
            else -> {
                val webView = sessions.existing(tabId) ?: return
                bridgeHosts[tabId]?.prepareFor(target.uri)
                binding.errorState.visibility = View.GONE
                webView.loadUrl(target.uri.toString())
            }
        }
    }

    private fun updateNavigationButtons(webView: WebView) {
        binding.back.isEnabled = webView.canGoBack()
        binding.forward.isEnabled = webView.canGoForward()
        if (::backCallback.isInitialized) {
            backCallback.isEnabled =
                webView.canGoBack() || coordinator.tabs.state.value.tabs.size > 1
        }
    }

    private fun updateBackCallback(state: TabState) {
        if (!::backCallback.isInitialized) return
        backCallback.isEnabled =
            currentWebView?.canGoBack() == true || state.tabs.size > 1
    }

    private fun addCurrentBookmark() {
        val tabId = coordinator.tabs.state.value.activeTabId
        scope.launch {
            val saved = coordinator.addBookmark(tabId)
            val message = if (saved == null) {
                "Bookmark tidak disimpan dari tab privat"
            } else {
                "Bookmark tersimpan"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTabsDialog() {
        val state = coordinator.tabs.state.value
        val labels = state.tabs.mapIndexed { index, tab ->
            val privacy = if (tab.isPrivate) "[Privat] " else ""
            val title = tab.title.ifBlank { tab.url }
            (index + 1).toString() + ". " + privacy + title
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.tabs_title)
            .setItems(labels) { _, which ->
                scope.launch { coordinator.selectTab(state.tabs[which].id) }
            }
            .setPositiveButton(R.string.new_tab) { _, _ ->
                scope.launch { coordinator.newTab(isPrivate = false) }
            }
            .setNegativeButton(R.string.close_active_tab) { _, _ ->
                closeActiveTab()
            }
            .show()
    }

    private fun showBookmarks() {
        scope.launch {
            val items = coordinator.bookmarks().first()
            if (items.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = items.map { it.title.ifBlank { it.url } }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.bookmarks_title)
                .setItems(labels) { _, which -> navigate(items[which].url) }
                .show()
        }
    }

    private fun showHistory() {
        scope.launch {
            val items = coordinator.history().first()
            if (items.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_history, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = items.map { it.title.ifBlank { it.url } }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.history_title)
                .setItems(labels) { _, which -> navigate(items[which].url) }
                .show()
        }
    }

    private fun showMenu(anchor: View) = PopupMenu(this, anchor).apply {
        menu.add(getString(R.string.new_tab)).setOnMenuItemClickListener {
            scope.launch { coordinator.newTab(isPrivate = false) }
            true
        }
        menu.add(getString(R.string.new_private_tab)).setOnMenuItemClickListener {
            if (!sessions.supportsPrivateProfiles()) {
                Toast.makeText(this@MainActivity, R.string.private_unsupported, Toast.LENGTH_LONG).show()
            } else {
                scope.launch { coordinator.newTab(isPrivate = true) }
            }
            true
        }
        menu.add(getString(R.string.bookmarks_title)).setOnMenuItemClickListener {
            showBookmarks()
            true
        }
        menu.add(getString(R.string.history_title)).setOnMenuItemClickListener {
            showHistory()
            true
        }
        menu.add("Unduhan").setOnMenuItemClickListener {
            showDownloads()
            true
        }
        menu.add("Metrik performa").setOnMenuItemClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("V0.9D Performance")
                .setMessage(metrics.snapshotText())
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }
        menu.add("Hapus riwayat").setOnMenuItemClickListener {
            scope.launch {
                coordinator.clearHistory()
                Toast.makeText(this@MainActivity, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
            }
            true
        }
        menu.add("Brankas").setOnMenuItemClickListener {
            val ok = DataVault.isUnlocked() || DataVault.unlock(this@MainActivity)
            val msg = if (ok) DataVault.lockedSlots(this@MainActivity) else "Vault terkunci"
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            true
        }
        menu.add("Kunci brankas").setOnMenuItemClickListener {
            DataVault.lockNow()
            Toast.makeText(this@MainActivity, "DEK dihapus dari memori", Toast.LENGTH_SHORT).show()
            true
        }
        menu.add("Hapus data sesi").setOnMenuItemClickListener {
            clearSessionData()
            true
        }
        show()
    }

    private fun closeActiveTab() {
        val id = coordinator.tabs.state.value.activeTabId
        bridgeHosts.remove(id)?.clear()
        sessions.release(id)
        if (currentWebView?.parent == null) currentWebView = null
        scope.launch { coordinator.closeTab(id) }
    }

    private fun clearSessionData() {
        bridgeHosts.values.forEach(MediaBridgeHost::clear)
        bridgeHosts.clear()
        sessions.destroyAll()
        currentWebView = null
        binding.webContainer.removeAllViews()
        prefs.edit().remove("lastUrl").apply()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }

        scope.launch {
            coordinator.clearHistory()
            coordinator.resetSession()
            Toast.makeText(this@MainActivity, R.string.session_cleared, Toast.LENGTH_LONG).show()
        }
    }

    private fun offerDownload(tabId: String, candidate: MediaCandidate) {
        val webView = sessions.existing(tabId) ?: return
        val label = candidate.url.lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?: candidate.url.host.orEmpty()

        AlertDialog.Builder(this)
            .setTitle(R.string.download_media_title)
            .setMessage(label)
            .setPositiveButton(R.string.download_action) { _, _ ->
                scope.launch {
                    handleDownloadResult(
                        enqueueMedia(
                            url = candidate.url.toString(),
                            userAgent = webView.settings.userAgentString,
                            contentDisposition = null,
                            mimeType = null,
                            sourcePage = webView.url,
                        ),
                    )
                }
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private suspend fun enqueueMedia(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        sourcePage: String?,
    ): ManagedDownloadResult {
        return if (isHls(url, mimeType)) {
            hlsDownloads.enqueue(
                manifestUrl = url,
                userAgent = userAgent,
                sourcePage = sourcePage,
            )
        } else {
            downloads.enqueue(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                sourcePage = sourcePage,
            )
        }
    }

    private fun isHls(url: String, mimeType: String?): Boolean {
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (normalizedMime == "application/vnd.apple.mpegurl" ||
            normalizedMime == "application/x-mpegurl" ||
            normalizedMime == "audio/mpegurl"
        ) {
            return true
        }
        return runCatching { Uri.parse(url).lastPathSegment.orEmpty().lowercase() }
            .getOrDefault("")
            .endsWith(".m3u8")
    }

    private fun handleDownloadResult(result: ManagedDownloadResult) {
        val message = when (result) {
            is ManagedDownloadResult.Enqueued -> "Masuk antrean: " + result.fileName
            is ManagedDownloadResult.Rejected -> result.reason
            is ManagedDownloadResult.Failed -> "Unduhan gagal: " + result.reason
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showDownloads() {
        scope.launch {
            runCatching { downloads.refreshAll() }
            val items = downloads.observe().first()
            if (items.isEmpty()) {
                Toast.makeText(this@MainActivity, "Belum ada unduhan", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = items.map(::downloadLabel).toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Antrean unduhan")
                .setItems(labels) { _, which -> showDownloadActions(items[which]) }
                .setNegativeButton(R.string.cancel_action, null)
                .show()
        }
    }

    private fun downloadLabel(item: DownloadEntity): String {
        val progress = if (item.totalBytes > 0) {
            val percent = ((item.bytesDownloaded * 100L) / item.totalBytes)
                .coerceIn(0, 100)
            " · " + percent + "%"
        } else if (item.bytesDownloaded > 0) {
            " · " + (item.bytesDownloaded / 1024L) + " KiB"
        } else {
            ""
        }
        val kind = if (item.kind == DownloadKinds.HLS_VOD) "HLS" else "FILE"
        return "[" + kind + "] " + item.fileName + "
" + item.status + progress
    }

    private fun showDownloadActions(item: DownloadEntity) {
        val actions = mutableListOf<String>()
        if (item.status == DownloadStatuses.QUEUED ||
            item.status == DownloadStatuses.RUNNING ||
            item.status == DownloadStatuses.PAUSED
        ) {
            actions += "Batalkan"
        }
        if (item.status == DownloadStatuses.FAILED ||
            item.status == DownloadStatuses.CANCELED ||
            item.status == DownloadStatuses.MISSING
        ) {
            actions += "Coba lagi"
        }
        actions += "Tutup"

        AlertDialog.Builder(this)
            .setTitle(item.fileName)
            .setMessage(downloadLabel(item))
            .setItems(actions.toTypedArray()) { dialog, which ->
                when (actions[which]) {
                    "Batalkan" -> scope.launch {
                        val ok = if (item.kind == DownloadKinds.HLS_VOD) {
                            hlsDownloads.cancel(item.downloadId)
                        } else {
                            downloads.cancel(item.downloadId)
                        }
                        Toast.makeText(
                            this@MainActivity,
                            if (ok) "Unduhan dibatalkan" else "Tidak dapat membatalkan",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    "Coba lagi" -> scope.launch {
                        val result = if (item.kind == DownloadKinds.HLS_VOD) {
                            hlsDownloads.retry(item.downloadId)
                        } else {
                            downloads.retry(
                                item.downloadId,
                                currentWebView?.settings?.userAgentString,
                            )
                        }
                        handleDownloadResult(result)
                    }
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun trimInactiveSessions(state: TabState, maxResident: Int) {
        val resident = state.tabs.filter { sessions.existing(it.id) != null }
        if (resident.size <= maxResident) return

        resident
            .filter { it.id != state.activeTabId }
            .sortedBy { it.lastAccessedAt }
            .take(resident.size - maxResident)
            .forEach { tab ->
                bridgeHosts.remove(tab.id)?.clear()
                sessions.release(tab.id)
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(::navigate)
    }

    override fun onResume() {
        super.onResume()
        if (::coordinator.isInitialized) {
            sessions.resumeActive(coordinator.tabs.state.value.activeTabId)
        }
    }

    override fun onPause() {
        if (::sessions.isInitialized) sessions.pauseAll()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::coordinator.isInitialized &&
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            trimInactiveSessions(coordinator.tabs.state.value, maxResident = 2)
        }
    }

    override fun onStop() {
        DataVault.lockNow()
        if (::metrics.isInitialized) metrics.flush()
        super.onStop()
    }

    override fun onDestroy() {
        if (::metrics.isInitialized) metrics.flush()
        bridgeHosts.values.forEach(MediaBridgeHost::clear)
        bridgeHosts.clear()
        if (::sessions.isInitialized) sessions.destroyAll()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val DOWNLOAD_REFRESH_MS = 1500L
    }
}
