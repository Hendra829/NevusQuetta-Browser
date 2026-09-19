package com.nevus.quetta.download

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadPolicyTest {
    @Test
    fun `only valid HTTPS targets are accepted`() {
        assertNull(DownloadPolicy.validateHttps("http://example.com/file.mp4"))
        assertNull(DownloadPolicy.validateHttps("https://user:pass@example.com/file.mp4"))
        assertNull(DownloadPolicy.validateHttps("https://localhost/file.mp4"))
        assertEquals(
            "https://example.com/file.mp4",
            DownloadPolicy.validateHttps("https://example.com/file.mp4").toString(),
        )
    }

    @Test
    fun `cookie sharing is same origin only`() {
        val target = Uri.parse("https://cdn.example.com/file.mp4")
        assertTrue(
            DownloadPolicy.safeCookieTarget(
                "https://cdn.example.com/watch",
                target,
            ),
        )
        assertFalse(
            DownloadPolicy.safeCookieTarget(
                "https://app.example.com/watch?token=secret",
                target,
            ),
        )
    }

    @Test
    fun `cross origin referrer is reduced to origin`() {
        val target = Uri.parse("https://cdn.example.com/file.mp4")
        assertEquals(
            "https://app.example.com/",
            DownloadPolicy.safeReferrer(
                "https://app.example.com/watch?token=secret",
                target,
            ),
        )
    }

    @Test
    fun `filename removes traversal and reserved characters`() {
        val result = DownloadPolicy.sanitizeFileName("../bad:name?.mp4")
        assertFalse(result.contains(".."))
        assertFalse(result.contains(':'))
        assertFalse(result.contains('?'))
        assertTrue(result.endsWith(".mp4"))
    }
}
