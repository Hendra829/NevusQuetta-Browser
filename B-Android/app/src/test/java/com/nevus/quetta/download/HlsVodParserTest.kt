package com.nevus.quetta.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsVodParserTest {
    private val parser = HlsVodParser()

    @Test
    fun parsesHttpsVodIncludingOpaqueSegments() {
        val result = parser.parse(
            "https://media.example.com/path/index.m3u8",
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            seg1
            #EXTINF:10,
            https://cdn.example.com/blob?id=2
            #EXT-X-ENDLIST
            """.trimIndent(),
        )

        require(result is HlsParseResult.Media)
        assertEquals(
            listOf(
                "https://media.example.com/path/seg1",
                "https://cdn.example.com/blob?id=2",
            ),
            result.segments,
        )
        assertEquals(0, result.skippedGapSegments)
    }

    @Test
    fun skipsExtXGapWithoutRequestingMissingSegment() {
        val result = parser.parse(
            "https://media.example.com/index.m3u8",
            """
            #EXTM3U
            #EXTINF:10,
            seg1.ts
            #EXT-X-GAP
            #EXTINF:10,
            missing.ts
            #EXTINF:10,
            seg2.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )

        require(result is HlsParseResult.Media)
        assertEquals(
            listOf(
                "https://media.example.com/seg1.ts",
                "https://media.example.com/seg2.ts",
            ),
            result.segments,
        )
        assertEquals(1, result.skippedGapSegments)
    }

    @Test
    fun rejectsDiscontinuityUntilTimelineRemuxIsAvailable() {
        val result = parser.parse(
            "https://media.example.com/index.m3u8",
            """
            #EXTM3U
            #EXTINF:10,
            seg1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:10,
            seg2.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        assertTrue(result is HlsParseResult.Rejected)
        assertTrue((result as HlsParseResult.Rejected).reason.contains("DISCONTINUITY"))
    }

    @Test
    fun rejectsEncryptedPlaylist() {
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
    fun rejectsLivePlaylistWithoutEndlist() {
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
    fun parsesMasterAndRejectsExternalAudioOnlyVariant() {
        val result = parser.parse(
            "https://media.example.com/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Indonesia",LANGUAGE="id",DEFAULT=YES,AUTOSELECT=YES,URI="audio/id.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2500000,RESOLUTION=1920x1080,FRAME-RATE=60.0,AUDIO="aud"
            high/index.m3u8
            """.trimIndent(),
        )

        require(result is HlsParseResult.Master)
        assertEquals(1, result.variants.size)
        assertEquals("aud", result.variants.single().audioGroup)
        assertEquals(1920, result.variants.single().width)
        assertEquals(1080, result.variants.single().height)
        assertEquals("id", result.audioRenditions.single().language)
        assertEquals(
            "https://media.example.com/audio/id.m3u8",
            result.audioRenditions.single().url,
        )
        assertTrue(parser.selectVariant(result) is HlsVariantSelection.Rejected)
    }

    @Test
    fun selectsInBandAudioVariantAndHonorsQualityPolicy() {
        val result = parser.parse(
            "https://media.example.com/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Muxed",DEFAULT=YES,AUTOSELECT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO="aud"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=3840x2160,AUDIO="aud"
            high.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=7680x4320,AUDIO="aud"
            too-high.m3u8
            """.trimIndent(),
        )

        require(result is HlsParseResult.Master)
        assertNull(result.audioRenditions.single().url)
        val selection = parser.selectVariant(
            result,
            HlsSelectionPolicy(maxWidth = 3840, maxHeight = 2160),
        )
        require(selection is HlsVariantSelection.Selected)
        assertEquals(
            "https://media.example.com/high.m3u8",
            selection.variant.url,
        )
        assertFalse(selection.variant.width == 7680)
    }
}
