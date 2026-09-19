package com.nevus.quetta

import android.app.Application
import com.nevus.quetta.cleanup.CleanupManager
import com.nevus.quetta.cleanup.CleanupPolicy
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.nevus.quetta.data.BrowserDatabase
import kotlin.concurrent.thread

class NevusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeRuleset()
        clearOrphanPrivateProfiles()
        BrowserDatabase.get(this)
        scheduleBoundedCleanup()
    }

    private fun initializeRuleset() {
        val loaded = runCatching {
            NativeGuard.loadRuleset(this)
            true
        }.getOrDefault(false)

        getSharedPreferences("nevus_runtime", MODE_PRIVATE)
            .edit()
            .putBoolean("rulesetLoaded", loaded)
            .putInt("rulesetVersion", if (loaded) NativeGuard.rulesetVersion else 0)
            .apply()
    }

    private fun clearOrphanPrivateProfiles() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return

        var attempted = 0
        var failures = 0
        runCatching {
            val store = ProfileStore.getInstance()
            store.getAllProfileNames()
                .filter { it.startsWith(PRIVATE_PROFILE_PREFIX) }
                .forEach { name ->
                    attempted += 1
                    if (runCatching { store.deleteProfile(name) }.isFailure) {
                        failures += 1
                    }
                }
        }.onFailure {
            failures += 1
        }

        getSharedPreferences(RUNTIME_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(PRIVATE_CLEANUP_ATTEMPTED, attempted)
            .putInt(PRIVATE_CLEANUP_FAILURES, failures)
            .apply()
    }

    private fun scheduleBoundedCleanup() {
        val maintenance = getSharedPreferences("nevus_maintenance", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = maintenance.getLong("lastCacheCleanup", 0L)
        if (now - last < CLEANUP_INTERVAL_MS) return

        thread(name = "nevus-cache-cleanup", isDaemon = true) {
            val manager = CleanupManager(listOf(cacheDir, codeCacheDir))
            val report = manager.run(
                CleanupPolicy(
                    roots = listOf(cacheDir, codeCacheDir),
                    olderThanMillis = CACHE_MAX_AGE_MS,
                    nowMillis = now,
                ),
            )
            maintenance.edit()
                .putLong("lastCacheCleanup", now)
                .putInt("lastDeletedFiles", report.deleted)
                .putLong("lastReclaimedBytes", report.bytesReclaimed)
                .apply()
        }
    }

    private companion object {
        const val PRIVATE_PROFILE_PREFIX = "nevus_private_"
        const val RUNTIME_PREFS = "nevus_runtime"
        const val PRIVATE_CLEANUP_ATTEMPTED = "privateCleanupAttempted"
        const val PRIVATE_CLEANUP_FAILURES = "privateCleanupFailures"
        const val CLEANUP_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val CACHE_MAX_AGE_MS = 3L * 24L * 60L * 60L * 1000L
    }
}
