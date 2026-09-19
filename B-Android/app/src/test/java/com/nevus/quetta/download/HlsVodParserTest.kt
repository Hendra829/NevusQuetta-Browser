package com.nevus.quetta.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsVodParserTest {
    private val parser = HlsVodParser()

    @Test
    fun `parses HTTPS VOD media playlist and skips gap segments`() {
        val result = parser.parse(
            "https://media.example.com/path/index.m3u8",
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            seg1.ts
            #EXT-X-GAP
            #EXTINF:10,
            missing.ts
            #EXTINF:10,
            https://cdn.example.com/seg2.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )

        require(result is HlsParseResult.Media)
        assertEquals(
            listOf(
                "https://media.example.com/path/seg1.ts",
                "https://cdn.example.com/seg2.ts",
            ),
            result.segments,
        )
        assertEquals(1, result.skippedGapSegments)
    }

    @Test
    fun `rejects encrypted playlist`() {
        val result = parser.parse(
            "https://media.example.com/index.m3u8",
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:10,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertTrue(result is HlsParseResult.Rejected)
    }

    @Test
    fun `rejects live playlist without endlist`() {
        val result = parser.parse(
            "https://media.example.com/index.m3u8",
            """
            #EXTM3U
            #EXTINF:10,
            seg.ts
            """.trimIndent(),
        )
        assertTrue(result is HlsParseResult.Rejected)
    }

    @Test
    fun `rejects discontinuity instead of producing corrupt concatenation`() {
        val result = parser.parse(
            "https://media.example.com/index.m3u8",
            """
            #EXTM3U
            #EXTINF:10,
            a.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:10,
            b.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        require(result is HlsParseResult.Rejected)
        assertTrue(result.reason.contains("DISCONTINUITY"))
    }

    @Test
    fun `parses master metadata and selects highest compatible embedded-audio variant`() {
        val result = parser.parse(
            "https://media.example.com/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="external",NAME="Bahasa",DEFAULT=YES,AUTOSELECT=YES,URI="audio/id.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AVERAGE-BANDWIDTH=900000,RESOLUTION=1280x720,FRAME-RATE=30,CODECS="avc1.4d401f,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=3840x2160,FRAME-RATE=60,CODECS="hvc1.1.6.L120,mp4a.40.2"
            uhd/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=3840x2160,AUDIO="external"
            uhd-external/index.m3u8
            """.trimIndent(),
        )

        require(result is HlsParseResult.Master)
        assertEquals(3, result.variants.size)
        assertEquals(1, result.audioRenditions.size)

        val selected = parser.selectVariant(result)
        require(selected is HlsVariantSelection.Selected)
        assertEquals(
            "https://media.example.com/uhd/index.m3u8",
            selected.variant.url,
        )
        assertEquals(3840, selected.variant.width)
        assertEquals(2160, selected.variant.height)
    }

    @Test
    fun `rejects master when every variant requires external audio remux`() {
        val result = parser.parse(
            "https://media.example.com/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Bahasa",DEFAULT=YES,AUTOSELECT=YES,URI="audio/id.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO="audio"
            video/index.m3u8
            """.trimIndent(),
        )
        require(result is HlsParseResult.Master)
        val selected = parser.selectVariant(result)
        assertTrue(selected is HlsVariantSelection.Rejected)
    }
}
