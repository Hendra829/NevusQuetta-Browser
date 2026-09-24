package com.nevus.quetta.web

import android.net.Uri
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeMediaBridgeTest {
    /**
     * AUDIT-REPORT.md A-SMB-01: kandidat media WAJIB se-origin dengan halaman.
     * Sebelum perbaikan, CDN lintas-origin diterima ("atau secure CDN") sehingga
     * situs/iframe mana pun bisa memicu unduhan ke host penyerang.
     */
    @Test
    fun `accepts same origin https media only`() {
        val top = Uri.parse("https://site.test/page")
        val same = SafeMediaBridge.validate(
            top,
            """{"type":"media","url":"https://site.test/video.mp4"}""",
        )
        assertTrue(same is BridgeDecision.Accepted)
    }

    @Test
    fun `rejects cross origin cdn media`() {
        val top = Uri.parse("https://site.test/page")
        val cdn = SafeMediaBridge.validate(
            top,
            """{"type":"media","url":"https://cdn.test/master.m3u8"}""",
        )
        assertTrue(cdn is BridgeDecision.Rejected)
    }

    @Test
    fun `rejects subdomain and port origin mismatch`() {
        val top = Uri.parse("https://site.test/page")
        val rejected = listOf(
            """{"type":"media","url":"https://evil.site.test/v.mp4"}""",
            """{"type":"media","url":"https://site.test:8443/v.mp4"}""",
            """{"type":"media","url":"https://site.test.evil.test/v.mp4"}""",
        )
        rejected.forEach {
            assertTrue(it, SafeMediaBridge.validate(top, it) is BridgeDecision.Rejected)
        }
    }

    @Test
    fun `rejects insecure malformed and unsupported payloads`() {
        val top = Uri.parse("https://site.test/page")
        val rejected = listOf(
            """{"type":"media","url":"http://site.test/video.mp4"}""",
            """{"type":"other","url":"https://site.test/video.mp4"}""",
            """{"type":"media","url":"https://user@site.test/video.mp4"}""",
            """{"type":"media","url":"https://site.test/video.mp4#frag"}""",
            "not-json",
        )
        rejected.forEach {
            assertTrue(it, SafeMediaBridge.validate(top, it) is BridgeDecision.Rejected)
        }
    }

    @Test
    fun `rejects oversized payload`() {
        val top = Uri.parse("https://site.test")
        val huge = "a".repeat(SafeMediaBridge.MAX_PAYLOAD_BYTES)
        val payload = """{"type":"media","url":"https://site.test/$huge"}"""
        assertTrue(SafeMediaBridge.validate(top, payload) is BridgeDecision.Rejected)
    }

    @Test
    fun `same origin uses effective default port`() {
        assertTrue(
            SafeMediaBridge.sameOrigin(
                Uri.parse("https://site.test/a"),
                Uri.parse("https://site.test:443/b"),
            ),
        )
    }
}
