package com.nevus.quetta.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabManagerTest {
    private var nextId = 0
    private var now = 100L

    private fun manager(maxTabs: Int = 12) = TabManager(
        homeUrl = "https://home.test/",
        maxTabs = maxTabs,
        idFactory = { "tab-" + (++nextId) },
        clock = { ++now },
    )

    @Test
    fun `new select update and close keep one valid active tab`() {
        val manager = manager()
        val first = manager.state.value.activeTab
        assertNotNull(manager.newTab("https://two.test/", false))
        val second = manager.state.value.activeTab

        manager.updateNavigation(second.id, "https://two.test/page", "Two")
        assertEquals("Two", manager.state.value.activeTab.title)
        assertTrue(manager.selectTab(first.id))
        assertEquals(first.id, manager.state.value.activeTabId)

        assertTrue(manager.closeTab(first.id))
        assertEquals(second.id, manager.state.value.activeTabId)
        assertTrue(manager.closeTab(second.id))
        assertEquals(1, manager.state.value.tabs.size)
        assertEquals("https://home.test/", manager.state.value.activeTab.url)
    }

    @Test
    fun `manager enforces tab cap`() {
        val manager = manager(maxTabs = 2)
        assertNotNull(manager.newTab())
        assertNull(manager.newTab())
        assertEquals(2, manager.state.value.tabs.size)
    }

    @Test
    fun `private tabs never enter persistent snapshot or restore`() {
        val manager = manager()
        val normal = manager.newTab("https://normal.test/", false)!!
        manager.newTab("https://private.test/", true)
        assertFalse(manager.persistentSnapshot().any(BrowserTab::isPrivate))

        manager.restore(
            listOf(
                normal,
                BrowserTab("p", "https://private.test/", "", true, 1),
                BrowserTab("unsafe", "http://unsafe.test/", "", false, 2),
            ),
            preferredActiveId = "p",
        )

        assertEquals(listOf(normal.id), manager.state.value.tabs.map(BrowserTab::id))
        assertEquals(normal.id, manager.state.value.activeTabId)
    }
}
