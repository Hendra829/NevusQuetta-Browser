package com.nevus.quetta.download

import java.net.URI

sealed interface HlsParseResult {
    data class Media(
        val segments: List<HlsSegment>,
        val initSegment: String?,
        val gapCount: Int,
        val hasDiscontinuity: Boolean,
    ) : HlsParseResult

    data class Master(
        val variants: List<HlsVariant>,
        val audioRenditions: List<HlsRendition>,
    ) : HlsParseResult

    data class Rejected(val reason: String) : HlsParseResult
}

data class HlsSegment(
    val url: String,
    val discontinuityBefore: Boolean,
)

data class HlsVariant(
    val url: String,
    val bandwidth: Long?,
    val audioGroup: String?,
)

data class HlsRendition(
    val groupId: String,
    val name: String?,
    val url: String?,
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
        var pendingGap = false
        var pendingDiscontinuity = false
        var gapCount = 0
        var hasDiscontinuity = false
        val segments = mutableListOf<HlsSegment>()

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val quoted = attribute(line, "URI")
                        ?: return HlsParseResult.Rejected("EXT-X-MAP tanpa URI valid")
                    val resolved = resolveHttps(base, quoted)
                        ?: return HlsParseResult.Rejected("Init segment bukan HTTPS")
                    if (initSegment != null && initSegment != resolved) {
                        return HlsParseResult.Rejected(
                            "Perubahan EXT-X-MAP di tengah playlist belum didukung",
                        )
                    }
                    initSegment = resolved
                }
                line.equals("#EXT-X-GAP", ignoreCase = true) -> pendingGap = true
                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    pendingDiscontinuity = true
                    hasDiscontinuity = true
                }
                !line.startsWith("#") -> {
                    val resolved = resolveHttps(base, line)
                        ?: return HlsParseResult.Rejected("Segment bukan HTTPS")
                    if (pendingGap) {
                        gapCount += 1
                        pendingGap = false
                        pendingDiscontinuity = false
                    } else {
                        segments += HlsSegment(
                            url = resolved,
                            discontinuityBefore = pendingDiscontinuity,
                        )
                        pendingDiscontinuity = false
                    }
                }
            }
        }

        if (segments.isEmpty()) return HlsParseResult.Rejected("Playlist tidak memiliki segment aktif")
        return HlsParseResult.Media(
            segments = segments,
            initSegment = initSegment,
            gapCount = gapCount,
            hasDiscontinuity = hasDiscontinuity,
        )
    }

    private fun parseMaster(base: URI, lines: List<String>): HlsParseResult {
        val renditions = lines
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .filter { attribute(it, "TYPE")?.equals("AUDIO", ignoreCase = true) == true }
            .mapNotNull { line ->
                val group = attribute(line, "GROUP-ID") ?: return@mapNotNull null
                val rawUri = attribute(line, "URI")
                val resolved = rawUri?.let { resolveHttps(base, it) }
                if (rawUri != null && resolved == null) return HlsParseResult.Rejected(
                    "Audio rendition bukan HTTPS",
                )
                HlsRendition(
                    groupId = group,
                    name = attribute(line, "NAME"),
                    url = resolved,
                )
            }

        val variants = mutableListOf<HlsVariant>()
        var pendingBandwidth: Long? = null
        var pendingAudioGroup: String? = null
        var awaitingUrl = false

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    pendingBandwidth = attribute(line, "BANDWIDTH")?.toLongOrNull()
                    pendingAudioGroup = attribute(line, "AUDIO")
                    awaitingUrl = true
                }
                awaitingUrl && !line.startsWith("#") -> {
                    val resolved = resolveHttps(base, line)
                        ?: return HlsParseResult.Rejected("Variant HLS bukan HTTPS")
                    variants += HlsVariant(
                        url = resolved,
                        bandwidth = pendingBandwidth,
                        audioGroup = pendingAudioGroup,
                    )
                    pendingBandwidth = null
                    pendingAudioGroup = null
                    awaitingUrl = false
                }
            }
        }

        if (variants.isEmpty()) return HlsParseResult.Rejected("Master playlist tanpa variant")
        return HlsParseResult.Master(variants, renditions)
    }

    private fun attribute(line: String, name: String): String? {
        val body = line.substringAfter(':', "")
        val regex = Regex(
            "(?:^|,)" + Regex.escape(name) + "=(?:\"([^\"]*)\"|([^,]*))",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(body) ?: return null
        return match.groupValues[1].takeIf(String::isNotEmpty)
            ?: match.groupValues[2].trim().takeIf(String::isNotEmpty)
    }

    private fun resolveHttps(base: URI, raw: String): String? {
        val uri = runCatching { base.resolve(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toString()
    }
}
