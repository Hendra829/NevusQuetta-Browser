package com.nevus.quetta.web

import android.net.Uri
import org.json.JSONObject
import java.net.IDN
import java.util.Locale

data class MediaCandidate(
    val url: Uri,
    val hint: String?,
)

sealed interface BridgeDecision {
    data class Accepted(val candidate: MediaCandidate) : BridgeDecision
    data class Rejected(val reason: String) : BridgeDecision
}

object SafeMediaBridge {
    const val MAX_PAYLOAD_BYTES = 16_384
    private val allowedHints = setOf("hls", "dash", "video", "audio", "unknown")

    /**
     * Memvalidasi pesan bridge media.
     *
     * AUDIT-REPORT.md A-SMB-01: versi sebelumnya hanya memeriksa bahwa kandidat
     * memakai HTTPS dan TIDAK membandingkan origin kandidat dengan origin halaman.
     * Akibatnya skrip dari situs mana pun (atau iframe pihak ketiga yang lolos ke
     * main frame) dapat menyuruh aplikasi mengunduh URL CDN milik penyerang —
     * bertentangan dengan invarian "normalized origin kandidat harus sama dengan
     * top-level origin" yang tertulis di spesifikasi desain.
     *
     * Sekarang kandidat WAJIB se-origin dengan halaman. CDN lintas-origin yang
     * sah tetap dapat diunduh lewat tombol unduh situs itu sendiri / melalui
     * `DownloadListener`, sehingga tidak ada fungsionalitas yang hilang.
     */
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
        if (candidate.encodedFragment != null) {
            return BridgeDecision.Rejected("media-fragment-not-allowed")
        }
        if (!sameOrigin(topLevel, candidate)) {
            return BridgeDecision.Rejected("cross-origin-media")
        }

        val rawHint = json.optString("hint").trim().lowercase(Locale.US)
        val hint = rawHint.takeIf { it in allowedHints }

        return BridgeDecision.Accepted(
            MediaCandidate(
                url = candidate,
                hint = hint,
            ),
        )
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
