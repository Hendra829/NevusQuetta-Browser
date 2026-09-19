package com.nevus.quetta

import android.app.DownloadManager
import android.content.Context
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.nevus.quetta.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val startPage = "https://www.google.com/"
    private val prefs by lazy { getSharedPreferences("nevus", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        NativeGuard.loadRuleset(this)
        DataVault.unlock(this)
        configureWebView()
        binding.back.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.reload.setOnClickListener { binding.webView.reload() }
        binding.menu.setOnClickListener { showMenu(it) }
        binding.address.setOnEditorActionListener { _, _, event ->
            if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) { navigate(binding.address.text.toString()); true } else false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (binding.webView.canGoBack()) binding.webView.goBack() else finish() }
        })
        if (savedInstanceState == null) navigate(intent?.dataString ?: prefs.getString("lastUrl", startPage)!!) else binding.webView.restoreState(savedInstanceState)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        addJavascriptInterface(BrowserBridge { runOnUiThread { offerDownload(it) } }, "NevusBridge")
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return if (scheme == "http" || scheme == "https") false else true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host.orEmpty()
                return if (NativeGuard.isBlockedHost(host)) WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))) else null
            }
            override fun onPageFinished(view: WebView, url: String) {
                binding.address.setText(url)
                prefs.edit().putString("lastUrl", url).apply()
                view.evaluateJavascript("javascript:(()=>{if(window.__nq)return;window.__nq=1;document.addEventListener('play',e=>{const u=e.target.currentSrc||e.target.src;if(u&&u.startsWith('https://'))NevusBridge.mediaFound(u)},true)})()", null)
            }
        }
        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        })
    }

    private fun navigate(raw: String) {
        val value = raw.trim()
        val url = when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            value.contains('.') && !value.contains(' ') -> "https://$value"
            else -> "https://www.google.com/search?q=${Uri.encode(value)}"
        }
        binding.webView.loadUrl(url)
    }

    private fun showMenu(anchor: View) = PopupMenu(this, anchor).apply {
        menu.add("Beranda").setOnMenuItemClickListener { navigate(startPage); true }
        menu.add("Brankas").setOnMenuItemClickListener {
            val ok = if (DataVault.isUnlocked()) true else DataVault.unlock(this@MainActivity)
            val msg = if (ok) DataVault.lockedSlots(this@MainActivity) else "Vault terkunci"
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show(); true
        }
        menu.add("Kunci brankas").setOnMenuItemClickListener {
            DataVault.lockNow(); Toast.makeText(this@MainActivity, "DEK dihapus dari memori", Toast.LENGTH_SHORT).show(); true
        }
        menu.add("Hapus data sesi").setOnMenuItemClickListener {
            binding.webView.clearHistory(); binding.webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null); Toast.makeText(this@MainActivity, "Data sesi dihapus", Toast.LENGTH_SHORT).show(); true
        }
        show()
    }

    private fun offerDownload(url: String) = Toast.makeText(this, "Media terdeteksi; gunakan kontrol unduh situs bila diizinkan.", Toast.LENGTH_SHORT).show()

    private fun download(url: String, userAgent: String, disposition: String, mime: String) {
        if (!url.startsWith("https://")) { Toast.makeText(this, "Hanya unduhan HTTPS diizinkan", Toast.LENGTH_SHORT).show(); return }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            addRequestHeader("User-Agent", userAgent)
            setMimeType(mime)
            setTitle(URLUtil.guessFileName(url, disposition, mime))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, disposition, mime))
        }
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(this, "Unduhan dimulai", Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(outState: Bundle) { binding.webView.saveState(outState); super.onSaveInstanceState(outState) }
    override fun onStop() { DataVault.lockNow(); super.onStop() }
    override fun onDestroy() { binding.webView.removeJavascriptInterface("NevusBridge"); binding.webView.destroy(); super.onDestroy() }
}
