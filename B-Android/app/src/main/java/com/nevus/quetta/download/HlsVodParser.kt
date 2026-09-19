package com.nevus.quetta.download

import java.net.URI

sealed interface HlsParseResult {
    data class Media(
        val segments: List<String>,
        val initSegment: String?,
    ) : HlsParseResult

    data class Master(
        val variants: List<HlsVariant>,
    ) : HlsParseResult

    data class Rejected(val reason: String) : HlsParseResult
}

data class HlsVariant(
    val url: String,
    val bandwidth: Long?,
)

class HlsVodParser {
    fun parse(manifestUrl: String, body: String): HlsParseResult {
        val base = runCatching { URI(manifestUrl) }.getOrNull()
            ?: return HlsParseResult.Rejected("Manifest URL tidak valid")
        if (!base.scheme.equals("https", ignoreCase = true)) {
            return HlsParseResult.Rejected("HLS hanya diizinkan melalui HTTPS")
        }

        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U") {
            return HlsParseResult.Rejected("Bukan playlist HLS")
        }
        if (lines.any {
                it.startsWith("#EXT-X-KEY:", ignoreCase = true) &&
                    !it.contains("METHOD=NONE", ignoreCase = true)
            }) {
            return HlsParseResult.Rejected("HLS terenkripsi/DRM tidak didukung")
        }
        if (lines.any { it.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) }) {
            return HlsParseResult.Rejected("Session key HLS tidak didukung")
        }

        if (lines.any { it.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) }) {
            return parseMaster(base, lines)
        }

        if (lines.none { it.equals("#EXT-X-ENDLIST", ignoreCase = true) }) {
            return HlsParseResult.Rejected("Live HLS tanpa ENDLIST tidak diunduh")
        }
        if (lines.any { it.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) }) {
            return HlsParseResult.Rejected("HLS BYTERANGE belum didukung")
        }

        var initSegment: String? = null
        val segments = mutableListOf<String>()
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val quoted = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                        ?: return HlsParseResult.Rejected("EXT-X-MAP tanpa URI valid")
                    initSegment = resolveHttps(base, quoted)
                        ?: return HlsParseResult.Rejected("Init segment bukan HTTPS")
                }
                !line.startsWith("#") -> {
                    val resolved = resolveHttps(base, line)
                        ?: return HlsParseResult.Rejected("Segment bukan HTTPS")
                    segments += resolved
                }
            }
        }

        if (segments.isEmpty()) return HlsParseResult.Rejected("Playlist tidak memiliki segment")
        return HlsParseResult.Media(segments, initSegment)
    }

    private fun parseMaster(base: URI, lines: List<String>): HlsParseResult {
        val variants = mutableListOf<HlsVariant>()
        var pendingBandwidth: Long? = null
        var awaitingUrl = false

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    pendingBandwidth = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    awaitingUrl = true
                }
                awaitingUrl && !line.startsWith("#") -> {
                    val resolved = resolveHttps(base, line)
                        ?: return HlsParseResult.Rejected("Variant HLS bukan HTTPS")
                    variants += HlsVariant(resolved, pendingBandwidth)
                    pendingBandwidth = null
                    awaitingUrl = false
                }
            }
        }

        if (variants.isEmpty()) return HlsParseResult.Rejected("Master playlist tanpa variant")
        return HlsParseResult.Master(variants)
    }

    private fun resolveHttps(base: URI, raw: String): String? {
        val uri = runCatching { base.resolve(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toString()
    }
}
