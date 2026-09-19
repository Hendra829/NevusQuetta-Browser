package com.nevus.quetta.download

import android.net.Uri
import java.net.IDN
import java.net.InetAddress
import java.util.Locale

object DownloadPolicy {
    private val controlOrReserved = Regex("[\\/:*?\"<>|\\u0000-\\u001F]")
    private val repeatedSpace = Regex("\\s+")
    private val dangerousDotSegments = Regex("""(^|[\\/])\.\.?([\\/]|$)""")

    fun validateHttps(raw: String): Uri? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (uri.userInfo != null || uri.encodedAuthority?.contains('@') == true) return null

        val asciiHost = runCatching {
            if (host.contains(':')) host.lowercase(Locale.US)
            else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
        }.getOrNull() ?: return null

        if (asciiHost.isBlank() || asciiHost.length > 253) return null
        if (asciiHost == "localhost" || asciiHost.endsWith(".localhost") || asciiHost.endsWith(".local")) {
            return null
        }
        val port = if (uri.port == -1) 443 else uri.port
        if (port !in 1..65535) return null
        return uri
    }

    /**
     * Downloader/background fetches are deliberately restricted to public destinations.
     * DNS is resolved on an IO dispatcher before a connection is opened so private/link-local
     * addresses cannot be reached through obvious DNS aliases.
     */
    fun resolvesToPublicAddress(uri: Uri): Boolean {
        val validated = validateHttps(uri.toString()) ?: return false
        val host = validated.host ?: return false
        return runCatching {
            val addresses = InetAddress.getAllByName(host)
            addresses.isNotEmpty() && addresses.all(::isPublicAddress)
        }.getOrDefault(false)
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

    fun redactedForStorage(uri: Uri): String {
        val validated = validateHttps(uri.toString()) ?: return ""
        return Uri.Builder()
            .scheme("https")
            .encodedAuthority(validated.encodedAuthority)
            .encodedPath(validated.encodedPath?.takeIf(String::isNotBlank) ?: "/")
            .build()
            .toString()
    }

    fun resolveRedirect(current: Uri, location: String): Uri? {
        val resolved = runCatching {
            java.net.URI(current.toString()).resolve(location).toString()
        }.getOrNull() ?: return null
        return validateHttps(resolved)
    }

    fun parseContentRangeStart(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return Regex("""bytes\s+(\d+)-\d+/\d+|\*""", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return header.substringAfterLast('/', missingDelimiterValue = "")
            .takeIf { it != "*" }
            ?.toLongOrNull()
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        if (bytes.size == 16) {
            // fc00::/7 unique-local and IPv4-mapped private ranges.
            val first = bytes[0].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return false
            val mapped = bytes.sliceArray(0..9).all { it.toInt() == 0 } &&
                bytes[10].toInt() == 0xff && bytes[11].toInt() == 0xff
            if (mapped) {
                val v4 = InetAddress.getByAddress(bytes.sliceArray(12..15))
                return isPublicAddress(v4)
            }
        }
        return true
    }

    private fun effectivePort(uri: Uri): Int =
        if (uri.port == -1) 443 else uri.port
}
