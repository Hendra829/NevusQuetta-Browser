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
            assertEquals(1, repository.bookmarks().first().size)
            assertEquals(1, repository.history().first().size)
            assertEquals(2, repository.tabs().first().size)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(1500)
            onView(withId(R.id.tabs)).check(matches(withText("2")))
        }
        println("NEVUS_PROCESS_DEATH_PERSISTENCE=PASS")
    }
}
