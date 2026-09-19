package com.nevus.quetta

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `production manifest forbids cleartext and backup`() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }

    @Test
    fun `manifest uses Nevus application and exports only launcher`() {
        assertEquals(NevusApplication::class.java.name, context.applicationInfo.className)

        val launcher = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }
        val activities = context.packageManager.queryIntentActivities(launcher, 0)

        assertEquals(listOf(MainActivity::class.java.name), activities.map { it.activityInfo.name })
        assertTrue(activities.single().activityInfo.exported)
        assertFalse(context.applicationInfo.enabled.not())
    }
}
