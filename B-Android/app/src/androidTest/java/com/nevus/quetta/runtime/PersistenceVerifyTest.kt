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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceVerifyTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun persistentStateSurvivesProcessDeath() {
        runBlocking(Dispatchers.IO) {
            val repository = BrowserRepository(
                BrowserDatabase.get(context),
                ioDispatcher = Dispatchers.IO,
            )

            val bookmarks = repository.bookmarks().first()
            val history = repository.history().first()
            val tabs = repository.tabs().first()

            assertTrue(
                bookmarks.any { item ->
                    item.normalizedUrl == "https://example.com/"
                },
            )
            assertTrue(
                history.any { item ->
                    item.url == "https://example.org/" &&
                        item.title == "Runtime History"
                },
            )
            assertEquals(2, tabs.size)
            assertTrue(tabs.any { item -> item.tabId == "runtime-a" })
            assertTrue(tabs.any { item -> item.tabId == "runtime-b" })
        }

        val cleanupFailures = context
            .getSharedPreferences("nevus_runtime", Context.MODE_PRIVATE)
            .getInt("privateCleanupFailures", 0)
        assertEquals(0, cleanupFailures)

        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(1500)
            onView(withId(R.id.tabs)).check(matches(withText("2")))
        }
        println("NEVUS_PROCESS_DEATH_PERSISTENCE=PASS")
    }
}
