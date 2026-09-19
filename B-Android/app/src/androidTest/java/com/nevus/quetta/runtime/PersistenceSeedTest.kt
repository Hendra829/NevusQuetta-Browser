package com.nevus.quetta.runtime

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevus.quetta.MainActivity
import com.nevus.quetta.R
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.BrowserRepository
import com.nevus.quetta.data.TabEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceSeedTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedPersistentStateAndConfirmUiRestore() = runBlocking(Dispatchers.IO) {
        val database = BrowserDatabase.get(context)
        database.clearAllTables()
        val repository = BrowserRepository(database, ioDispatcher = Dispatchers.IO)

        repository.upsertBookmark(
            rawUrl = "https://example.com/",
            title = "Runtime Bookmark",
            createdAt = 1000L,
        )
        repository.recordVisit(
            rawUrl = "https://example.org/",
            title = "Runtime History",
            isPrivate = false,
            visitedAt = 2000L,
        )
        repository.replaceSession(
            listOf(
                TabEntity(
                    tabId = "runtime-a",
                    url = "https://example.com/",
                    title = "Example A",
                    isPrivate = false,
                    isActive = true,
                    position = 0,
                    updatedAt = 3000L,
                ),
                TabEntity(
                    tabId = "runtime-b",
                    url = "https://example.org/",
                    title = "Example B",
                    isPrivate = false,
                    isActive = false,
                    position = 1,
                    updatedAt = 4000L,
                ),
            ),
        )

        assertEquals(1, repository.bookmarks().first().size)
        assertEquals(1, repository.history().first().size)
        assertEquals(2, repository.tabs().first().size)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityScenario.launch(MainActivity::class.java).use {
                SystemClock.sleep(1500)
                onView(withId(R.id.tabs)).check(matches(withText("2")))
            }
        }
        println("NEVUS_PERSISTENCE_SEED=PASS")
    }
}
