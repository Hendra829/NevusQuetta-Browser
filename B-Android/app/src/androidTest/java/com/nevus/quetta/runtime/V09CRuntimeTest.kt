package com.nevus.quetta.runtime

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadEntity
import com.nevus.quetta.data.DownloadKinds
import com.nevus.quetta.data.DownloadStatuses
import com.nevus.quetta.download.DownloadCenter
import com.nevus.quetta.download.DownloadRepository
import com.nevus.quetta.download.ManagedDownloadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V09CRuntimeTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun managedDownloadMetadataIsRedacted() = runBlocking {
        val database = BrowserDatabase.get(context)
        val repository = DownloadRepository(database)
        val center = DownloadCenter(context, database)

        val result = center.enqueue(
            url = "https://example.com/?token=nevus-secret",
            userAgent = "NevusQuetta-V09C-Gate",
            contentDisposition = null,
            mimeType = "text/html",
            sourcePage = "https://example.com/",
        )
        require(result is ManagedDownloadResult.Enqueued)

        val stored = repository.get(result.downloadId)
        requireNotNull(stored)
        assertEquals("https://example.com/", stored.url)
        assertFalse(stored.url.contains("token", ignoreCase = true))
        assertFalse(stored.url.contains("nevus-secret"))
        assertTrue(center.cancel(stored))
        center.deleteMetadata(stored)
    }

    @Test
    fun hlsWorkerCompletesGapPlaylistAndRedactsMetadata() = runBlocking {
        val database = BrowserDatabase.get(context)
        val repository = DownloadRepository(database)
        val center = DownloadCenter(context, database)

        val result = center.enqueue(
            url = HLS_MANIFEST_URL,
            userAgent = "NevusQuetta-V09C-HLS-Gate",
            contentDisposition = null,
            mimeType = "application/vnd.apple.mpegurl",
            sourcePage = "https://httpbin.org/",
            hint = "hls",
        )
        require(result is ManagedDownloadResult.Enqueued)

        val queued = repository.get(result.downloadId)
        requireNotNull(queued)
        assertEquals(DownloadKinds.HLS_VOD, queued.kind)
        assertEquals("https://httpbin.org/", queued.url)
        assertFalse(queued.url.contains("token", ignoreCase = true))

        val completed = waitFor(result.downloadId, repository, 45_000L) {
            it.status == DownloadStatuses.COMPLETED
        }

        assertEquals(3_072L, completed.bytesDownloaded)
        assertEquals(3_072L, completed.totalBytes)
        assertNotNull(completed.localUri)
        assertTrue(completed.sha256?.matches(Regex("[0-9a-f]{64}")) == true)

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
            "NEVUS_HLS_RUNTIME=PASS bytes=" + completed.bytesDownloaded +
                " sha256=" + completed.sha256,
        )
    }

    @Test
    fun downloadQueueIsObservable() = runBlocking {
        val repository = DownloadRepository(BrowserDatabase.get(context))
        repository.observe().first()
        assertTrue(true)
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
            val item = repository.get(downloadId)
            if (item != null) {
                last = item
                if (condition(item)) return item
                if (item.status == DownloadStatuses.FAILED) {
                    error("HLS failed: " + item.errorCode)
                }
            }
            SystemClock.sleep(250)
        }
        error("HLS timeout; last=" + (last?.status ?: "missing"))
    }

    private companion object {
        const val HLS_MANIFEST_URL =
            "https://httpbin.org/base64/" +
                "I0VYVE0zVQojRVhULVgtVEFSR0VURFVSQVRJT046MQojRVhUSU5GOjEsCmh0dHBzOi8vaHR0cGJpbi5vcmcvcmFuZ2UvMTAyNAojRVhULVgtR0FQCiNFWFRJTkY6MSwKaHR0cHM6Ly9odHRwYmluLm9yZy9yYW5nZS81MTIKI0VYVElORjoxLApodHRwczovL2h0dHBiaW4ub3JnL3JhbmdlLzIwNDgKI0VYVC1YLUVORExJU1QK" +
                "?token=top-secret"
    }
}
