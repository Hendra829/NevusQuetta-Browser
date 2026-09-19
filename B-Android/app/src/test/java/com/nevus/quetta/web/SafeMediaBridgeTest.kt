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
    @Test
    fun `accepts https media from same origin or secure CDN`() {
        val top = Uri.parse("https://site.test/page")
        val same = SafeMediaBridge.validate(
            top,
            """{"type":"media","url":"https://site.test/video.mp4"}""",
        )
        val cdn = SafeMediaBridge.validate(
            top,
            """{"type":"media","url":"https://cdn.test/master.m3u8"}""",
        )
        assertTrue(same is BridgeDecision.Accepted)
        assertTrue(cdn is BridgeDecision.Accepted)
    }

    @Test
    fun `rejects insecure malformed and unsupported payloads`() {
        val top = Uri.parse("https://site.test/page")
        val rejected = listOf(
            """{"type":"media","url":"http://site.test/video.mp4"}""",
            """{"type":"other","url":"https://site.test/video.mp4"}""",
            """{"type":"media","url":"https://user@site.test/video.mp4"}""",
            "not-json",
        )
        rejected.forEach {
            assertTrue(SafeMediaBridge.validate(top, it) is BridgeDecision.Rejected)
        }
    }

    @Test
    fun `rejects oversized payload`() {
        val top = Uri.parse("https://site.test")
        val huge = "a".repeat(SafeMediaBridge.MAX_PAYLOAD_BYTES)
        val payload = """{"type":"media","url":"https://site.test/$huge"}"""
        assertTrue(SafeMediaBridge.validate(top, payload) is BridgeDecision.Rejected)
    }
}
