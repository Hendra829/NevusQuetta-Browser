package com.nevus.quetta.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserRepositoryTest {
    private lateinit var database: BrowserDatabase
    private lateinit var repository: BrowserRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, BrowserDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = BrowserRepository(database, ioDispatcher = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `bookmark upsert de-duplicates normalized URL`() = runTest {
        repository.upsertBookmark("http://example.com", "First", 10)
        repository.upsertBookmark("https://example.com/", "Updated", 20)

        val items = repository.bookmarks().first()
        assertEquals(1, items.size)
        assertEquals("https://example.com/", items.single().url)
        assertEquals("Updated", items.single().title)
        assertEquals(20, items.single().createdAt)
    }

    @Test
    fun `private visits are never persisted`() = runTest {
        val stored = repository.recordVisit(
            "https://example.com",
            "Private",
            isPrivate = true,
            visitedAt = 10,
        )

        assertFalse(stored)
        assertTrue(repository.history().first().isEmpty())
    }

    @Test
    fun `session replacement excludes private tabs and preserves active metadata`() = runTest {
        repository.replaceSession(
            listOf(
                TabEntity("a", "https://a.test/", "A", false, true, 2, 20),
                TabEntity("private", "https://private.test/", "P", true, false, 1, 10),
                TabEntity("b", "https://b.test/", "B", false, false, 0, 30),
            ),
        )

        val tabs = repository.tabs().first()
        assertEquals(listOf("b", "a"), tabs.map(TabEntity::tabId))
        assertTrue(tabs.single { it.tabId == "a" }.isActive)
        assertTrue(tabs.none(TabEntity::isPrivate))
    }
}
