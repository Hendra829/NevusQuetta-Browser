package com.nevus.quetta.runtime

import android.content.ComponentCallbacks2
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.nevus.quetta.MainActivity
import com.nevus.quetta.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun rotationLifecycleInsetsAndMemoryPressureRemainHealthy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.root)).check(matches(isDisplayed()))
            onView(withId(R.id.address)).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(R.id.root)
                val insets = ViewCompat.getRootWindowInsets(root)
                val bars = insets?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )
                if (bars != null) {
                    assertTrue(root.paddingTop >= bars.top)
                    assertTrue(root.paddingBottom >= bars.bottom)
                    assertTrue(root.paddingLeft >= bars.left)
                    assertTrue(root.paddingRight >= bars.right)
                }
            }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            onView(withId(R.id.tabs)).check(matches(isDisplayed()))

            scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            device.waitForIdle()
            SystemClock.sleep(1000)
            onView(withId(R.id.tabs)).check(matches(isDisplayed()))

            scenario.onActivity {
                it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            device.waitForIdle()
            SystemClock.sleep(1000)
            onView(withId(R.id.address)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun multiTabSwitchAndPrivateProfilePathAreOperational() {
        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(1000)

            createNormalTab()
            createNormalTab()
            onView(withId(R.id.tabs)).check(matches(withText("3")))

            val supportsProfiles = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
            println("NEVUS_PRIVATE_PROFILE_SUPPORTED=" + supportsProfiles)

            onView(withId(R.id.menu)).perform(click())
            onView(withText(R.string.new_private_tab)).perform(click())
            device.waitForIdle()
            SystemClock.sleep(1000)

            if (supportsProfiles) {
                onView(withId(R.id.privateIndicator)).check(matches(isDisplayed()))
                val names = readProfileNamesOnMainThread()
                assertTrue(names.any { name -> name.startsWith("nevus_private_") })

                onView(withId(R.id.tabs)).perform(click())
                onView(withText(R.string.close_active_tab)).perform(click())
                device.waitForIdle()
                SystemClock.sleep(1000)

                assertEquals(3, readTabCount())
                onView(withId(R.id.privateIndicator)).check(
                    matches(
                        androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                            androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE,
                        ),
                    ),
                )
            } else {
                assertEquals(3, readTabCount())
            }
        }
    }

    private fun createNormalTab() {
        onView(withId(R.id.tabs)).perform(click())
        onView(withText(R.string.new_tab)).perform(click())
        device.waitForIdle()
        SystemClock.sleep(500)
    }

    private fun readProfileNamesOnMainThread(): List<String> {
        var names: List<String> = emptyList()
        instrumentation.runOnMainSync {
            names = ProfileStore.getInstance().getAllProfileNames()
        }
        return names
    }

    private fun readTabCount(): Int {
        var value = -1
        onView(withId(R.id.tabs)).check { view, _ ->
            value = (view as android.widget.Button).text.toString().toInt()
        }
        return value
    }
}
