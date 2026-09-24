package com.nevus.quetta.download

import java.net.URI
import java.util.Locale

sealed interface HlsParseResult {
    data class Media(
        val segments: List<String>,
        val initSegment: String?,
        val skippedGapSegments: Int,
    ) : HlsParseResult

    data class Master(
        val variants: List<HlsVariant>,
        val audioRenditions: List<HlsAudioRendition>,
    ) : HlsParseResult

    data class Rejected(val reason: String) : HlsParseResult
}

data class HlsVariant(
    val url: String,
    val bandwidth: Long?,
    val averageBandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val codecs: String?,
    val audioGroup: String?,
)

data class HlsAudioRendition(
    val groupId: String,
    val name: String,
    val language: String?,
    val url: String?,
    val isDefault: Boolean,
    val autoSelect: Boolean,
    val channels: String?,
)

data class HlsSelectionPolicy(
    val maxWidth: Int = 3840,
    val maxHeight: Int = 2160,
    val maxBandwidth: Long = 50_000_000L,
)

sealed interface HlsVariantSelection {
    data class Selected(val variant: HlsVariant) : HlsVariantSelection
    data class Rejected(val reason: String) : HlsVariantSelection
}

class HlsVodParser {
    fun parse(manifestUrl: String, body: String): HlsParseResult {
        val base = runCatching { URI(manifestUrl) }.getOrNull()
            ?: return HlsParseResult.Rejected("Manifest URL tidak valid")
        if (!base.scheme.equals("https", ignoreCase = true) ||
            base.host.isNullOrBlank() ||
            base.userInfo != null
        ) {
            return HlsParseResult.Rejected("HLS hanya diizinkan melalui HTTPS valid")
        }

        val lines = body.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (lines.firstOrNull() != "#EXTM3U") {
            return HlsParseResult.Rejected("Bukan playlist HLS")
        }
        if (lines.size > MAX_LINES) {
            return HlsParseResult.Rejected("Playlist HLS terlalu kompleks")
        }
        if (lines.any {
                it.startsWith("#EXT-X-KEY:", ignoreCase = true) &&
                    !attribute(it, "METHOD").equals("NONE", ignoreCase = true)
            }
        ) {
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
            return HlsParseResult.Rejected("HLS BYTERANGE belum didukung secara aman")
        }
        if (lines.any { it.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) }) {
            return HlsParseResult.Rejected(
                "HLS DISCONTINUITY memerlukan remux timeline dan tidak digabung mentah",
            )
        }

        var initSegment: String? = null
        var skipNextSegment = false
        var skippedGaps = 0
        val segments = mutableListOf<String>()

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    if (line.contains("BYTERANGE=", ignoreCase = true)) {
                        return HlsParseResult.Rejected("EXT-X-MAP BYTERANGE belum didukung")
                    }
                    if (segments.isNotEmpty()) {
                        return HlsParseResult.Rejected(
                            "EXT-X-MAP setelah media segment memerlukan remux",
                        )
                    }
                    val raw = attribute(line, "URI")
                        ?: return HlsParseResult.Rejected("EXT-X-MAP tanpa URI valid")
                    val resolved = resolveHttps(base, raw)
                        ?: return HlsParseResult.Rejected("Init segment bukan HTTPS")
                    if (initSegment != null && initSegment != resolved) {
                        return HlsParseResult.Rejected(
                            "Perubahan EXT-X-MAP memerlukan remux",
                        )
                    }
                    initSegment = resolved
                }

                line.equals("#EXT-X-GAP", ignoreCase = true) -> {
                    skipNextSegment = true
                }

                !line.startsWith("#") -> {
                    val resolved = resolveHttps(base, line)
                        ?: return HlsParseResult.Rejected("Segment bukan HTTPS")
                    if (skipNextSegment) {
                        skippedGaps += 1
                        skipNextSegment = false
                    } else {
                        segments += resolved
                    }
                }
            }
        }

        if (skipNextSegment) {
            return HlsParseResult.Rejected("EXT-X-GAP tidak memiliki segment berikutnya")
        }
        if (segments.isEmpty()) {
            return HlsParseResult.Rejected("Playlist tidak memiliki segment yang dapat diunduh")
        }
        if (segments.size > MAX_SEGMENTS) {
            return HlsParseResult.Rejected("Jumlah segment HLS melebihi batas keamanan")
        }
        return HlsParseResult.Media(
            segments = segments,
            initSegment = initSegment,
            skippedGapSegments = skippedGaps,
        )
    }

    fun selectVariant(
        master: HlsParseResult.Master,
        policy: HlsSelectionPolicy = HlsSelectionPolicy(),
    ): HlsVariantSelection {
        val compatible = master.variants.filter { variant ->
            val widthOk = variant.width == null || variant.width <= policy.maxWidth
            val heightOk = variant.height == null || variant.height <= policy.maxHeight
            val bandwidth = variant.averageBandwidth ?: variant.bandwidth ?: 0L
            val bandwidthOk = bandwidth <= policy.maxBandwidth
            widthOk && heightOk && bandwidthOk && hasEmbeddedOrNoExternalAudio(
                variant,
                master.audioRenditions,
            )
        }

        val pool = compatible.ifEmpty {
            master.variants.filter {
                hasEmbeddedOrNoExternalAudio(it, master.audioRenditions)
            }
        }
        if (pool.isEmpty()) {
            return HlsVariantSelection.Rejected(
                "Master HLS hanya menyediakan audio rendition terpisah; remux audio/video diperlukan",
            )
        }

        val selected = pool.maxWithOrNull(
            compareBy<HlsVariant>(
                { (it.width ?: 0).toLong() * (it.height ?: 0).toLong() },
                { it.averageBandwidth ?: it.bandwidth ?: 0L },
                { it.frameRate ?: 0.0 },
            ),
        ) ?: return HlsVariantSelection.Rejected("Master playlist tanpa variant kompatibel")

        return HlsVariantSelection.Selected(selected)
    }

    private fun parseMaster(base: URI, lines: List<String>): HlsParseResult {
        val audioRenditions = lines
            .filter {
                it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                    attribute(it, "TYPE").equals("AUDIO", ignoreCase = true)
            }
            .mapNotNull { line ->
                val group = attribute(line, "GROUP-ID") ?: return@mapNotNull null
                val name = attribute(line, "NAME") ?: group
                val rawUri = attribute(line, "URI")
                val resolved = rawUri?.let { resolveHttps(base, it) }
                if (rawUri != null && resolved == null) return HlsParseResult.Rejected(
                    "Audio rendition bukan HTTPS",
                )
                HlsAudioRendition(
                    groupId = group,
                    name = name,
                    language = attribute(line, "LANGUAGE"),
                    url = resolved,
                    isDefault = yesNo(attribute(line, "DEFAULT")),
                    autoSelect = yesNo(attribute(line, "AUTOSELECT")),
                    channels = attribute(line, "CHANNELS"),
                )
            }

        val variants = mutableListOf<HlsVariant>()
        var pendingAttributes: Map<String, String>? = null

        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    pendingAttributes = attributes(line)
                }

                pendingAttributes != null && !line.startsWith("#") -> {
                    val attrs = pendingAttributes!!
                    val resolved = resolveHttps(base, line)
                        ?: return HlsParseResult.Rejected("Variant HLS bukan HTTPS")
                    val resolution = attrs["RESOLUTION"]
                        ?.split('x', 'X')
                        ?.takeIf { it.size == 2 }
                    variants += HlsVariant(
                        url = resolved,
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull(),
                        averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull(),
                        width = resolution?.getOrNull(0)?.toIntOrNull(),
                        height = resolution?.getOrNull(1)?.toIntOrNull(),
                        frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                        codecs = attrs["CODECS"],
                        audioGroup = attrs["AUDIO"],
                    )
                    pendingAttributes = null
                }
            }
        }

        if (pendingAttributes != null) {
            return HlsParseResult.Rejected("EXT-X-STREAM-INF tanpa URI variant")
        }
        if (variants.isEmpty()) {
            return HlsParseResult.Rejected("Master playlist tanpa variant")
        }
        if (variants.size > MAX_VARIANTS) {
            return HlsParseResult.Rejected("Jumlah variant HLS melebihi batas keamanan")
        }
        return HlsParseResult.Master(variants, audioRenditions)
    }

    private fun hasEmbeddedOrNoExternalAudio(
        variant: HlsVariant,
        renditions: List<HlsAudioRendition>,
    ): Boolean {
        val group = variant.audioGroup ?: return true
        val groupItems = renditions.filter { it.groupId == group }
        if (groupItems.isEmpty()) return true
        return groupItems.any { it.url == null && (it.isDefault || it.autoSelect) }
    }

    private fun resolveHttps(base: URI, raw: String): String? {
        val uri = runCatching { base.resolve(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toString()
    }

    private fun yesNo(value: String?): Boolean =
        value.equals("YES", ignoreCase = true)

    private fun attribute(line: String, name: String): String? =
        attributes(line)[name.uppercase(Locale.US)]

    private fun attributes(line: String): Map<String, String> {
        val body = line.substringAfter(':', "")
        if (body.isBlank()) return emptyMap()

        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            while (index < body.length && (body[index] == ',' || body[index].isWhitespace())) {
                index += 1
            }
            val keyStart = index
            while (index < body.length && body[index] != '=') index += 1
            if (index >= body.length) break
            val key = body.substring(keyStart, index).trim().uppercase(Locale.US)
            index += 1

            val value = if (index < body.length && body[index] == '"') {
                index += 1
                val start = index
                while (index < body.length && body[index] != '"') index += 1
                val parsed = body.substring(start, index.coerceAtMost(body.length))
                if (index < body.length) index += 1
                parsed
            } else {
                val start = index
                while (index < body.length && body[index] != ',') index += 1
                body.substring(start, index).trim()
            }
            if (key.isNotBlank()) result[key] = value
        }
        return result
    }

    private companion object {
        const val MAX_LINES = 50_000
        const val MAX_SEGMENTS = 20_000
        const val MAX_VARIANTS = 256
    }
}
