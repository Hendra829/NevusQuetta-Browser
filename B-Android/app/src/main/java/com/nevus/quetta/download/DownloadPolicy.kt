package com.nevus.quetta.download

import android.net.Uri
import java.net.IDN
import java.util.Locale

object DownloadPolicy {
    private val controlOrReserved = Regex("[\\/:*?\"<>|\u0000-\u001F]")
    private val repeatedSpace = Regex("\\s+")
    private val dangerousDotSegments = Regex("""(^|[\\/])\.\.?([\\/]|$)""")

    fun validateHttps(raw: String): Uri? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (uri.userInfo != null) return null

        val asciiHost = runCatching { IDN.toASCII(host) }.getOrNull() ?: return null
        if (asciiHost.isBlank() || asciiHost.length > 253) return null
        if (asciiHost.equals("localhost", ignoreCase = true)) return null
        return uri
    }

    fun sanitizeFileName(raw: String, fallback: String = "download"): String {
        val cleaned = raw
            .replace(dangerousDotSegments, "_")
            .replace(controlOrReserved, "_")
            .replace(repeatedSpace, " ")
            .trim()
            .trim('.')
            .take(180)
        return cleaned.ifBlank { fallback }
    }

    fun sameOrigin(a: Uri, b: Uri): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)

    fun originOnly(uri: Uri): String? {
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        val host = uri.host!!.lowercase(Locale.US)
        val authority = if (effectivePort(uri) == 443) host else host + ":" + effectivePort(uri)
        return "https://" + authority + "/"
    }

    fun safeReferrer(sourcePage: String?, target: Uri): String? {
        val source = sourcePage?.let(::validateHttps) ?: return null
        return if (sameOrigin(source, target)) source.toString() else originOnly(source)
    }

    fun safeCookieTarget(sourcePage: String?, target: Uri): Boolean {
        val source = sourcePage?.let(::validateHttps) ?: return false
        return sameOrigin(source, target)
    }

    private fun effectivePort(uri: Uri): Int =
        if (uri.port == -1) 443 else uri.port
}
