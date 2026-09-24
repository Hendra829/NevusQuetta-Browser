package com.nevus.quetta.performance

import android.content.Context
import kotlin.math.max

class RuntimePerformanceMetrics(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var webViewCreated = prefs.getLong(KEY_CREATED, 0)
    private var webViewRebinds = prefs.getLong(KEY_REBINDS, 0)
    private var webViewReuses = prefs.getLong(KEY_REUSES, 0)
    private var pageLoads = prefs.getLong(KEY_PAGE_LOADS, 0)
    private var totalPageLoadMs = prefs.getLong(KEY_PAGE_LOAD_MS, 0)
    private var worstPageLoadMs = prefs.getLong(KEY_WORST_PAGE_LOAD_MS, 0)

    @Synchronized
    fun recordWebViewCreated() {
        webViewCreated += 1
    }

    @Synchronized
    fun recordWebViewRebind() {
        webViewRebinds += 1
    }

    @Synchronized
    fun recordWebViewReuse() {
        webViewReuses += 1
    }

    @Synchronized
    fun recordPageLoad(durationMs: Long) {
        val safe = max(0, durationMs)
        pageLoads += 1
        totalPageLoadMs += safe
        worstPageLoadMs = max(worstPageLoadMs, safe)
    }

    @Synchronized
    fun snapshotText(): String {
        val average = if (pageLoads == 0L) 0 else totalPageLoadMs / pageLoads
        return buildString {
            append("WebView dibuat: ").append(webViewCreated)
            append("\nRebind container: ").append(webViewRebinds)
            append("\nReuse tanpa rebind: ").append(webViewReuses)
            append("\nPage load tercatat: ").append(pageLoads)
            append("\nRata-rata page load: ").append(average).append(" ms")
            append("\nTerburuk: ").append(worstPageLoadMs).append(" ms")
        }
    }

    @Synchronized
    fun flush() {
        prefs.edit()
            .putLong(KEY_CREATED, webViewCreated)
            .putLong(KEY_REBINDS, webViewRebinds)
            .putLong(KEY_REUSES, webViewReuses)
            .putLong(KEY_PAGE_LOADS, pageLoads)
            .putLong(KEY_PAGE_LOAD_MS, totalPageLoadMs)
            .putLong(KEY_WORST_PAGE_LOAD_MS, worstPageLoadMs)
            .apply()
    }

    private companion object {
        const val PREFS = "nevus_v09d_metrics"
        const val KEY_CREATED = "webViewCreated"
        const val KEY_REBINDS = "webViewRebinds"
        const val KEY_REUSES = "webViewReuses"
        const val KEY_PAGE_LOADS = "pageLoads"
        const val KEY_PAGE_LOAD_MS = "pageLoadMs"
        const val KEY_WORST_PAGE_LOAD_MS = "worstPageLoadMs"
    }
}
