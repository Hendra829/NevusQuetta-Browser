package com.nevus.quetta.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationControllerTest {
    private val controller = NavigationController()

    @Test
    fun `normalizes web addresses and searches terms`() {
        assertEquals(
            "https://example.com/",
            (controller.resolve("example.com") as NavigationTarget.Web).uri.toString(),
        )
        assertEquals(
            "https://example.com/a",
            (controller.resolve("http://example.com/a") as NavigationTarget.Web).uri.toString(),
        )
        assertEquals(
            "https://example.com:8443/a",
            (controller.resolve("example.com:8443/a") as NavigationTarget.Web).uri.toString(),
        )
        assertEquals(
            "https://www.google.com/search?q=dua%20kata",
            (controller.resolve(" dua kata ") as NavigationTarget.Search).uri.toString(),
        )
    }

    @Test
    fun `normalizes unicode host with idn`() {
        val resolved = controller.resolve("https://bücher.de/")
        assertTrue(resolved is NavigationTarget.Web)
        assertEquals("https://xn--bcher-kva.de/", resolved.uri.toString())
    }

    @Test
    fun `rejects dangerous and unsupported inputs`() {
        listOf(
            "",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,x",
            "intent://scan/",
            "ftp://example.com/file",
            "https://user:pass@example.com/",
            "https://example.com/\u0000x",
        ).forEach { raw ->
            assertTrue("$raw should be rejected", controller.resolve(raw) is NavigationTarget.Rejected)
        }
    }
}
