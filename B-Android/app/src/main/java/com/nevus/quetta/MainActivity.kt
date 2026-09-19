package com.nevus.quetta

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.nevus.quetta.databinding.ActivityMainBinding
import com.nevus.quetta.navigation.NavigationController
import com.nevus.quetta.navigation.NavigationTarget
import com.nevus.quetta.web.MediaBridgeHost
import com.nevus.quetta.web.MediaCandidate
import com.nevus.quetta.web.SecureWebViewFactory
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaBridgeHost: MediaBridgeHost
    private val startPage = "https://www.google.com/"
    private val prefs by lazy { getSharedPreferences("nevus", MODE_PRIVATE) }
    private val navigation = NavigationController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runCatching { NativeGuard.loadRuleset(this) }
        configureWebView()
        configureActions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            navigate(intent?.dataString ?: prefs.getString("lastUrl", startPage).orEmpty().ifBlank { startPage })
        } else {
            val restored = binding.webView.restoreState(savedInstanceState)
            if (restored == null) navigate(prefs.getString("lastUrl", startPage).orEmpty().ifBlank { startPage })
        }
    }

    private fun configureActions() {
        binding.back.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.reload.setOnClickListener { binding.webView.reload() }
        binding.menu.setOnClickListener { showMenu(it) }
        binding.address.setOnEditorActionListener { _, _, event ->
            if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate(binding.address.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun configureWebView() = with(binding.webView) {
        SecureWebViewFactory.harden(this, debuggingEnabled = BuildConfig.DEBUG)
        mediaBridgeHost = MediaBridgeHost(this@MainActivity, this) { candidate ->
            runOnUiThread { offerDownload(candidate) }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
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

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return when (request.url.scheme?.lowercase()) {
                    "https" -> {
                        mediaBridgeHost.prepareFor(request.url)
                        false
                    }
                    "http" -> {
                        navigate(request.url.toString())
                        true
                    }
                    else -> true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val host = request.url.host.orEmpty()
                return if (NativeGuard.isBlockedHost(host)) {
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
                binding.address.setText(url)
                binding.back.isEnabled = view.canGoBack()
                if (url.startsWith("https://")) {
                    prefs.edit().putString("lastUrl", url).apply()
                }
            }
        }

        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        })
    }

    private fun navigate(raw: String) {
        when (val target = navigation.resolve(raw)) {
            is NavigationTarget.Rejected -> {
                Toast.makeText(this, target.reason, Toast.LENGTH_SHORT).show()
            }
            else -> {
                mediaBridgeHost.prepareFor(target.uri)
                binding.webView.loadUrl(target.uri.toString())
            }
        }
    }

    private fun showMenu(anchor: View) = PopupMenu(this, anchor).apply {
        menu.add("Beranda").setOnMenuItemClickListener {
            navigate(startPage)
            true
        }
        menu.add("Brankas").setOnMenuItemClickListener {
            val ok = if (DataVault.isUnlocked()) true else DataVault.unlock(this@MainActivity)
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
            Toast.makeText(this@MainActivity, "Data sesi dihapus", Toast.LENGTH_SHORT).show()
            true
        }
        show()
    }

    private fun clearSessionData() {
        binding.webView.clearHistory()
        binding.webView.clearCache(true)
        binding.webView.clearFormData()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }
    }

    private fun offerDownload(candidate: MediaCandidate) {
        Toast.makeText(
            this,
            "Media HTTPS terdeteksi: ${candidate.url.host.orEmpty()}",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun download(url: String, userAgent: String, disposition: String, mime: String) {
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "Hanya unduhan HTTPS diizinkan", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = URLUtil.guessFileName(url, disposition, mime).take(180).ifBlank { "download" }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { addRequestHeader("Cookie", it) }
            binding.webView.url
                ?.takeIf { it.startsWith("https://") }
                ?.let { addRequestHeader("Referer", it) }
            setMimeType(mime)
            setTitle(fileName)
            setDescription("NevusQuetta download")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        runCatching {
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }.onSuccess {
            Toast.makeText(this, "Unduhan dimulai", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Unduhan gagal dimulai", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(::navigate)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            binding.webView.onResume()
            binding.webView.resumeTimers()
        }
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            binding.webView.onPause()
            binding.webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        binding.webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        DataVault.lockNow()
        super.onStop()
    }

    override fun onDestroy() {
        if (::mediaBridgeHost.isInitialized) mediaBridgeHost.clear()
        if (::binding.isInitialized) {
            binding.webView.stopLoading()
            binding.webView.destroy()
        }
        super.onDestroy()
    }
}
