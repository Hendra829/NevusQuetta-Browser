package com.nevus.quetta.runtime

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadEntity
import com.nevus.quetta.data.DownloadStatuses
import com.nevus.quetta.download.DownloadCenter
import com.nevus.quetta.download.DownloadRepository
import com.nevus.quetta.download.ManagedDownloadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadRuntimeTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun managedRangeDownloadPausesResumesAndCompletes() = runBlocking {
        val database = BrowserDatabase.get(context)
        val repository = DownloadRepository(database)
        val center = DownloadCenter(context, database)

        val result = center.enqueue(
            url = RANGE_URL,
            userAgent = "NevusQuetta-V09C-RuntimeGate",
            contentDisposition = null,
            mimeType = "application/octet-stream",
            sourcePage = "https://httpbin.org/",
        )
        require(result is ManagedDownloadResult.Enqueued)
        assertTrue("Preflight harus mendeteksi Range support", result.supportsResume)

        val running = waitFor(result.downloadId, repository, 45_000L) { item ->
            item.status == DownloadStatuses.RUNNING &&
                item.bytesDownloaded >= PAUSE_AFTER_BYTES
        }
        assertTrue(running.bytesDownloaded > 0L)
        assertTrue(running.bytesDownloaded < running.totalBytes)
        assertEquals("https://httpbin.org/", running.url)
        assertFalse(running.url.contains("token", ignoreCase = true))
        assertFalse(running.url.contains("nevus-secret"))

        assertTrue(center.pause(running))

        val paused = waitFor(result.downloadId, repository, 15_000L) { item ->
            item.status == DownloadStatuses.PAUSED
        }
        val pausedBytes = paused.bytesDownloaded
        assertTrue(pausedBytes > 0L)

        val retry = center.retry(paused)
        require(retry is ManagedDownloadResult.Enqueued)

        val completed = waitFor(result.downloadId, repository, 60_000L) { item ->
            item.status == DownloadStatuses.COMPLETED
        }

        assertEquals(EXPECTED_BYTES, completed.bytesDownloaded)
        assertEquals(EXPECTED_BYTES, completed.totalBytes)
        assertNotNull(completed.localUri)
        assertTrue(completed.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue(completed.bytesDownloaded >= pausedBytes)

        completed.localUri?.let { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                if (uri.scheme == "content") {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
        assertTrue(center.deleteMetadata(completed))
        assertTrue(repository.get(result.downloadId) == null)

        println(
            "NEVUS_MANAGED_RESUME=PASS pausedBytes=" + pausedBytes +
                " sha256=" + completed.sha256,
        )
    }

    private suspend fun waitFor(
        downloadId: String,
        repository: DownloadRepository,
        timeoutMs: Long,
        condition: (DownloadEntity) -> Boolean,
    ): DownloadEntity {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: DownloadEntity? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = repository.get(downloadId)
            if (current != null) {
                last = current
                if (condition(current)) return current
                if (current.status == DownloadStatuses.FAILED) {
                    error("Download failed: " + current.errorCode)
                }
            }
            SystemClock.sleep(250)
        }
        error(
            "Timeout waiting for download state; last=" +
                (last?.status ?: "missing") +
                " bytes=" + (last?.bytesDownloaded ?: -1L),
        )
    }

    private companion object {
        const val EXPECTED_BYTES = 1_048_576L
        const val PAUSE_AFTER_BYTES = 524_288L
        const val RANGE_URL =
            "https://httpbin.org/range/1048576?duration=8&chunk_size=65536&token=nevus-secret"
    }
}
