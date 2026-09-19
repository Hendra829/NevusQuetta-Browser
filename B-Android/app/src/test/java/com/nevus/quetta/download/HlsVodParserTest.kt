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
            result.segments.map(HlsSegment::url),
        )
        assertEquals(0, result.gapCount)
    }

    @Test
    fun skipsGapAndRecordsDiscontinuity() {
        val result = parser.parse(
            "https://media.example.com/index.m3u8",
            """
            #EXTM3U
            #EXTINF:10,
            seg1.ts
            #EXT-X-GAP
            #EXTINF:10,
            missing.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:10,
            seg2.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )

        require(result is HlsParseResult.Media)
        assertEquals(2, result.segments.size)
        assertEquals(1, result.gapCount)
        assertTrue(result.hasDiscontinuity)
        assertTrue(result.segments.last().discontinuityBefore)
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
    fun parsesMasterVariantsAndExternalAudioRendition() {
        val result = parser.parse(
            "https://media.example.com/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Indonesia",URI="audio/id.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="aud"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,AUDIO="aud"
            high/index.m3u8
            """.trimIndent(),
        )

        require(result is HlsParseResult.Master)
        assertEquals(2, result.variants.size)
        assertEquals(3_000_000L, result.variants.last().bandwidth)
        assertEquals("aud", result.variants.last().audioGroup)
        assertEquals(
            "https://media.example.com/high/index.m3u8",
            result.variants.last().url,
        )
        assertEquals(1, result.audioRenditions.size)
        assertEquals(
            "https://media.example.com/audio/id.m3u8",
            result.audioRenditions.single().url,
        )
    }

    @Test
    fun inBandAudioRenditionHasNoExternalUrl() {
        val result = parser.parse(
            "https://media.example.com/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Muxed"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aud"
            video.m3u8
            """.trimIndent(),
        )
        require(result is HlsParseResult.Master)
        assertNull(result.audioRenditions.single().url)
        assertFalse(result.variants.isEmpty())
    }
}
