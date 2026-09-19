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

        val hierarchicalScheme = HIERARCHICAL_SCHEME
            .find(value)
            ?.groupValues
            ?.get(1)
            ?.lowercase(Locale.US)

        if (hierarchicalScheme != null && hierarchicalScheme !in WEB_SCHEMES) {
            return NavigationTarget.Rejected("Skema tidak didukung")
        }

        val genericScheme = SCHEME
            .find(value)
            ?.groupValues
            ?.get(1)
            ?.lowercase(Locale.US)

        if (hierarchicalScheme == null &&
            genericScheme != null &&
            genericScheme !in WEB_SCHEMES &&
            !looksLikeHostPort(value)
        ) {
            return NavigationTarget.Rejected("Skema tidak didukung")
        }

        val candidate = when {
            hierarchicalScheme in WEB_SCHEMES -> value
            value.none(Char::isWhitespace) && looksLikeAddress(value) -> "https://$value"
            else -> return NavigationTarget.Search(searchUri(value))
        }

        return normalizeWeb(candidate)
    }

    private fun normalizeWeb(candidate: String): NavigationTarget {
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull()
            ?: return NavigationTarget.Rejected("Alamat tidak valid")

        val scheme = parsed.scheme?.lowercase(Locale.US)
        if (scheme !in WEB_SCHEMES) return NavigationTarget.Rejected("Skema tidak didukung")
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
            .encodedPath(parsed.encodedPath?.takeIf { it.isNotEmpty() } ?: "/")
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
        val authorityLike = value
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')

        if (authorityLike.startsWith('[')) return true
        val host = authorityLike.substringBefore(':')
        return host.equals("localhost", ignoreCase = true) ||
            host.contains('.') ||
            host.all { it.isDigit() || it == '.' }
    }

    private fun looksLikeHostPort(value: String): Boolean {
        val authorityLike = value
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')

        if (authorityLike.startsWith('[')) return true
        val colon = authorityLike.lastIndexOf(':')
        if (colon <= 0 || colon == authorityLike.lastIndex) return false

        val host = authorityLike.substring(0, colon)
        val port = authorityLike.substring(colon + 1).toIntOrNull() ?: return false
        if (port !in 1..65535) return false

        return host.equals("localhost", ignoreCase = true) ||
            host.contains('.') ||
            host.all { it.isDigit() || it == '.' }
    }

    private fun normalizeHost(host: String): String? = runCatching {
        if (host.contains(':')) host.lowercase(Locale.US)
        else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
        val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        val HIERARCHICAL_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
    }
}
