package com.nevus.quetta.web

import android.net.Uri
import org.json.JSONObject
import java.net.IDN
import java.util.Locale

data class MediaCandidate(val url: Uri)

sealed interface BridgeDecision {
    data class Accepted(val candidate: MediaCandidate) : BridgeDecision
    data class Rejected(val reason: String) : BridgeDecision
}

object SafeMediaBridge {
    const val MAX_PAYLOAD_BYTES = 16_384

    fun validate(topLevel: Uri, payload: String): BridgeDecision {
        if (origin(topLevel) == null) return BridgeDecision.Rejected("invalid-top-level-origin")
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            return BridgeDecision.Rejected("payload-too-large")
        }

        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return BridgeDecision.Rejected("malformed-json")
        if (json.optString("type") != "media") return BridgeDecision.Rejected("unsupported-type")

        val url = json.optString("url")
        if (url.isBlank()) return BridgeDecision.Rejected("missing-url")
        val candidate = runCatching { Uri.parse(url) }.getOrNull()
            ?: return BridgeDecision.Rejected("invalid-url")

        if (origin(candidate) == null) return BridgeDecision.Rejected("non-https-media")
        return BridgeDecision.Accepted(MediaCandidate(candidate))
    }

    fun sameOrigin(left: Uri, right: Uri): Boolean {
        val a = origin(left) ?: return false
        val b = origin(right) ?: return false
        return a == b
    }

    fun originRule(uri: Uri): String? {
        val value = origin(uri) ?: return null
        val host = if (value.host.contains(':')) "[" + value.host + "]" else value.host
        return if (value.port == 443) "https://" + host else "https://" + host + ":" + value.port
    }

    private fun origin(uri: Uri): Origin? {
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.encodedAuthority?.contains('@') == true) return null
        val host = uri.host ?: return null
        val normalizedHost = normalizeHost(host) ?: return null
        val port = if (uri.port == -1) 443 else uri.port
        if (port !in 1..65535) return null
        return Origin(normalizedHost, port)
    }

    private fun normalizeHost(host: String): String? = runCatching {
        if (host.contains(':')) host.lowercase(Locale.US)
        else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private data class Origin(val host: String, val port: Int)
}
