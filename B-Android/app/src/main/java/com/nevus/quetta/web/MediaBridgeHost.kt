package com.nevus.quetta.web

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MediaBridgeHost(
    context: Context,
    private val webView: WebView,
    private val onCandidate: (MediaCandidate) -> Unit,
) {
    private val script = context.assets.open("nevus_media.js").bufferedReader().use { it.readText() }
    private var scriptHandler: ScriptHandler? = null
    private var listenerInstalled = false
    private var expectedTopLevel: Uri? = null

    fun prepareFor(uri: Uri): Boolean {
        clear()
        val originRule = SafeMediaBridge.originRule(uri) ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return false
        }

        expectedTopLevel = uri
        val listener = WebViewCompat.WebMessageListener {
                _: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                _ ->
            val expected = expectedTopLevel
            val payload = message.data
            if (!isMainFrame ||
                expected == null ||
                payload == null ||
                !SafeMediaBridge.sameOrigin(expected, sourceOrigin)
            ) {
                return@WebMessageListener
            }
            when (val decision = SafeMediaBridge.validate(expected, payload)) {
                is BridgeDecision.Accepted -> onCandidate(decision.candidate)
                is BridgeDecision.Rejected -> Unit
            }
        }

        return runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                setOf(originRule),
                listener,
            )
            listenerInstalled = true
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                script,
                setOf(originRule),
            )
            true
        }.getOrElse {
            clear()
            false
        }
    }

    fun clear() {
        scriptHandler?.remove()
        scriptHandler = null
        expectedTopLevel = null
        if (listenerInstalled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
        }
        listenerInstalled = false
    }

    private companion object {
        const val BRIDGE_NAME = "NevusBridge"
    }
}
