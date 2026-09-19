package com.nevus.quetta.performance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuntimePerformanceMetricsTest {
    private lateinit var context: Context

    @Before
    fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nevus_v09d_metrics", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun recordsWebViewReuseRebindAndPageLoadMetrics() {
        val metrics = RuntimePerformanceMetrics(context)
        metrics.recordWebViewCreated()
        metrics.recordWebViewRebind()
        metrics.recordWebViewReuse()
        metrics.recordPageLoad(125)
        metrics.flush()

        val text = RuntimePerformanceMetrics(context).snapshotText()
        assertTrue(text.contains("WebView dibuat: 1"))
        assertTrue(text.contains("Rebind container: 1"))
        assertTrue(text.contains("Reuse tanpa rebind: 1"))
        assertTrue(text.contains("Rata-rata page load: 125 ms"))
    }
}
