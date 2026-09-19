package com.nevus.quetta.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsVodParserTest {
    private val parser = HlsVodParser()

    @Test
    fun `parses HTTPS VOD media playlist`() {
        val result = parser.parse(
            "https://media.example.com/path/index.m3u8",
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            seg1.ts
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
    fun `parses master variants and bandwidth`() {
        val result = parser.parse(
            "https://media.example.com/master.m3u8",
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000
            high/index.m3u8
            """.trimIndent(),
        )

        require(result is HlsParseResult.Master)
        assertEquals(2, result.variants.size)
        assertEquals(3_000_000L, result.variants.last().bandwidth)
        assertEquals(
            "https://media.example.com/high/index.m3u8",
            result.variants.last().url,
        )
    }
}
