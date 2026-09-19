package com.nevus.quetta.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadKinds
import com.nevus.quetta.download.DownloadRepository
import com.nevus.quetta.download.HlsDownloadCoordinator
import com.nevus.quetta.download.ManagedDownloadCoordinator
import com.nevus.quetta.download.ManagedDownloadResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class V09CRuntimeTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun managedDownloadRedactsQueryAndDoesNotClaimResume() = runBlocking {
        val repository = DownloadRepository(BrowserDatabase.get(context))
        val coordinator = ManagedDownloadCoordinator(context, repository)
        val result = coordinator.enqueue(
            url = "https://example.com/?token=nevus-secret",
            userAgent = "NevusQuetta-V09C-Gate",
            contentDisposition = null,
            mimeType = "text/html",
            sourcePage = "https://example.com/",
        )
        require(result is ManagedDownloadResult.Enqueued)
        assertFalse(result.supportsResume)

        val stored = repository.get(result.downloadId)
        requireNotNull(stored)
        assertFalse(stored.url.contains("token", ignoreCase = true))
        assertFalse(stored.url.contains("nevus-secret"))
        coordinator.cancel(result.downloadId)
    }

    @Test
    fun hlsCoordinatorRedactsMetadataAndSchedulesWorker() = runBlocking {
        val repository = DownloadRepository(BrowserDatabase.get(context))
        val coordinator = HlsDownloadCoordinator(context, repository)
        val result = coordinator.enqueue(
            manifestUrl = "https://example.com/media/master.m3u8?token=top-secret",
            userAgent = "NevusQuetta-V09C-Gate",
            sourcePage = "https://example.com/watch",
        )
        require(result is ManagedDownloadResult.Enqueued)
        val stored = repository.get(result.downloadId)
        requireNotNull(stored)
        assertTrue(stored.kind == DownloadKinds.HLS_VOD)
        assertFalse(stored.url.contains("token", ignoreCase = true))

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("nevus-hls-" + result.downloadId)
            .get(15, TimeUnit.SECONDS)
        assertTrue(work.isNotEmpty())
        coordinator.cancel(result.downloadId)
    }

    @Test
    fun downloadQueueIsObservable() = runBlocking {
        val repository = DownloadRepository(BrowserDatabase.get(context))
        repository.observe().first()
        assertTrue(true)
    }
}
