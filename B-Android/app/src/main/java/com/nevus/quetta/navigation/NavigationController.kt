package com.nevus.quetta.navigation

import android.net.Uri
import java.net.IDN
import java.util.Locale

sealed interface NavigationTarget {
    val uri: Uri

    data class Web(override val uri: Uri) : NavigationTarget
    data class Search(override val uri: Uri) : NavigationTarget
    data class Rejected(val reason: String) : NavigationTarget {
        override val uri: Uri = Uri.EMPTY
    }
}

class NavigationController {
    fun resolve(raw: String): NavigationTarget {
        val value = raw.trim()
        if (value.isEmpty()) return NavigationTarget.Rejected("Alamat kosong")
        if (value.any { it.code < 0x20 || it.code == 0x7f }) {
            return NavigationTarget.Rejected("Karakter kontrol tidak diizinkan")
        }

        val explicitScheme = SCHEME.find(value)?.groupValues?.get(1)?.lowercase(Locale.US)
        if (explicitScheme != null && explicitScheme !in setOf("http", "https")) {
            val hostPort = value.substringAfter(':', "")
            val looksLikeHostPort = hostPort.all(Char::isDigit) &&
                (value.substringBefore(':').equals("localhost", true) || value.substringBefore(':').contains('.'))
            if (!looksLikeHostPort) return NavigationTarget.Rejected("Skema tidak didukung")
        }

        val hasWhitespace = value.any(Char::isWhitespace)
        val candidate = when {
            explicitScheme == "http" || explicitScheme == "https" -> value
            !hasWhitespace && looksLikeAddress(value) -> "https://$value"
            else -> return NavigationTarget.Search(searchUri(value))
        }

        return normalizeWeb(candidate)
    }

    private fun normalizeWeb(candidate: String): NavigationTarget {
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
            ?: return NavigationTarget.Rejected("Alamat tidak valid")
        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in setOf("http", "https")) return NavigationTarget.Rejected("Skema tidak didukung")
        if (parsed.encodedAuthority?.contains('@') == true) {
            return NavigationTarget.Rejected("User-info pada alamat tidak diizinkan")
        }

        val host = parsed.host ?: return NavigationTarget.Rejected("Host tidak valid")
        val normalizedHost = normalizeHost(host) ?: return NavigationTarget.Rejected("Host tidak valid")
        val port = parsed.port
        if (port !in -1..65535 || port == 0) return NavigationTarget.Rejected("Port tidak valid")

        val authorityHost = if (normalizedHost.contains(':')) "[$normalizedHost]" else normalizedHost
        val authority = if (port == -1) authorityHost else "$authorityHost:$port"
        val normalized = Uri.Builder()
            .scheme("https")
            .encodedAuthority(authority)
            .encodedPath(parsed.encodedPath ?: "")
            .apply {
                parsed.encodedQuery?.let(::encodedQuery)
                parsed.encodedFragment?.let(::encodedFragment)
            }
            .build()

        return NavigationTarget.Web(normalized)
    }

    private fun searchUri(query: String): Uri = Uri.Builder()
        .scheme("https")
        .authority("www.google.com")
        .path("search")
        .appendQueryParameter("q", query)
        .build()

    private fun looksLikeAddress(value: String): Boolean {
        val hostPart = value
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (hostPart.equals("localhost", true) || hostPart.startsWith('[')) return true
        val withoutPort = hostPart.substringBeforeLast(':', hostPart)
        return withoutPort.contains('.') || withoutPort.all { it.isDigit() || it == '.' }
    }

    private fun normalizeHost(host: String): String? = runCatching {
        if (host.contains(':')) host.lowercase(Locale.US)
        else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    }
}
