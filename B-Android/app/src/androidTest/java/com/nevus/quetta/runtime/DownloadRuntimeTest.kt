package com.nevus.quetta.runtime

import android.app.DownloadManager
import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevus.quetta.download.DownloadCoordinator
import com.nevus.quetta.download.DownloadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadRuntimeTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realHttpsDownloadCompletes() {
        val result = DownloadCoordinator(context).enqueue(
            url = "https://example.com/",
            userAgent = "NevusQuetta-RuntimeGate",
            contentDisposition = null,
            mimeType = "text/html",
            sourcePage = "https://example.com/",
        )
        assertTrue(result is DownloadResult.Enqueued)
        val id = (result as DownloadResult.Enqueued).id
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        var status = DownloadManager.STATUS_PENDING
        var reason = 0
        repeat(60) {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor.moveToFirst()) {
                    status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                    )
                    reason = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                    )
                }
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL ||
                status == DownloadManager.STATUS_FAILED
            ) {
                return@repeat
            }
            SystemClock.sleep(1000)
        }

        println("NEVUS_DOWNLOAD_STATUS=" + status + " REASON=" + reason)
        assertEquals(DownloadManager.STATUS_SUCCESSFUL, status)
        manager.remove(id)
    }
}
