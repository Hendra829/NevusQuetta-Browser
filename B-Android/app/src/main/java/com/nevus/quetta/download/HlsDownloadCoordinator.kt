package com.nevus.quetta.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nevus.quetta.data.DownloadEntity
import com.nevus.quetta.data.DownloadKinds
import com.nevus.quetta.data.DownloadStatuses
import java.util.UUID
import java.util.concurrent.TimeUnit

class HlsDownloadCoordinator(
    private val context: Context,
    private val repository: DownloadRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun enqueue(
        manifestUrl: String,
        userAgent: String?,
    ): ManagedDownloadResult {
        val uri = DownloadPolicy.validateHttps(manifestUrl)
            ?: return ManagedDownloadResult.Rejected("Manifest HLS harus HTTPS valid")
        val downloadId = UUID.randomUUID().toString()
        val base = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.takeIf(String::isNotBlank)
            ?: "video"
        val fileName = DownloadPolicy.sanitizeFileName(base + "-hls")

        val now = System.currentTimeMillis()
        repository.upsert(
            DownloadEntity(
                downloadId = downloadId,
                systemDownloadId = null,
                url = uri.toString(),
                sourceOrigin = DownloadPolicy.originOnly(uri),
                fileName = fileName,
                mimeType = "application/vnd.apple.mpegurl",
                kind = DownloadKinds.HLS_VOD,
                status = DownloadStatuses.QUEUED,
                bytesDownloaded = 0,
                totalBytes = -1,
                supportsResume = false,
                localUri = null,
                errorCode = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        enqueueWork(downloadId, uri.toString(), userAgent)
        return ManagedDownloadResult.Enqueued(
            downloadId = downloadId,
            systemId = -1,
            fileName = fileName,
            supportsResume = false,
        )
    }

    suspend fun cancel(downloadId: String): Boolean {
        val item = repository.get(downloadId) ?: return false
        workManager.cancelUniqueWork(workName(downloadId))
        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.CANCELED,
            bytesDownloaded = item.bytesDownloaded,
            totalBytes = item.totalBytes,
            localUri = item.localUri,
            errorCode = null,
        )
        return true
    }

    suspend fun retry(
        downloadId: String,
        userAgent: String?,
    ): ManagedDownloadResult {
        val item = repository.get(downloadId)
            ?: return ManagedDownloadResult.Rejected("Metadata HLS tidak ditemukan")
        if (item.kind != DownloadKinds.HLS_VOD) {
            return ManagedDownloadResult.Rejected("Bukan pekerjaan HLS")
        }
        if (item.status != DownloadStatuses.FAILED &&
            item.status != DownloadStatuses.CANCELED
        ) {
            return ManagedDownloadResult.Rejected("HLS belum berada pada status retry")
        }
        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.QUEUED,
            bytesDownloaded = 0,
            totalBytes = -1,
            localUri = null,
            errorCode = null,
        )
        enqueueWork(downloadId, item.url, userAgent)
        return ManagedDownloadResult.Enqueued(
            downloadId = downloadId,
            systemId = -1,
            fileName = item.fileName,
            supportsResume = false,
        )
    }

    private fun enqueueWork(
        downloadId: String,
        manifestUrl: String,
        userAgent: String?,
    ) {
        val request = OneTimeWorkRequestBuilder<HlsVodDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .setInputData(
                workDataOf(
                    HlsVodDownloadWorker.KEY_DOWNLOAD_ID to downloadId,
                    HlsVodDownloadWorker.KEY_MANIFEST_URL to manifestUrl,
                    HlsVodDownloadWorker.KEY_USER_AGENT to userAgent,
                ),
            )
            .build()

        workManager.enqueueUniqueWork(
            workName(downloadId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun workName(downloadId: String): String =
        "nevus-hls-" + downloadId
}
