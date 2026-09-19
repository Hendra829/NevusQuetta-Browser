package com.nevus.quetta.download

import android.content.Context
import android.webkit.CookieManager
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
    context: Context,
    private val repository: DownloadRepository,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val secrets = DownloadSecretStore(appContext)

    suspend fun enqueue(
        manifestUrl: String,
        userAgent: String?,
        sourcePage: String?,
    ): ManagedDownloadResult {
        val uri = DownloadPolicy.validateHttps(manifestUrl)
            ?: return ManagedDownloadResult.Rejected("Manifest HLS harus HTTPS valid")
        if (!DownloadPolicy.resolvesToPublicAddress(uri)) {
            return ManagedDownloadResult.Rejected(
                "Tujuan HLS bukan alamat jaringan publik yang diizinkan",
            )
        }

        val downloadId = UUID.randomUUID().toString()
        val base = uri.lastPathSegment
            ?.substringBeforeLast('.')
            ?.takeIf(String::isNotBlank)
            ?: "video"
        val fileName = DownloadPolicy.sanitizeFileName(base + "-hls")

        val cookie = if (DownloadPolicy.safeCookieTarget(sourcePage, uri)) {
            CookieManager.getInstance().getCookie(uri.toString())
        } else {
            null
        }
        val secret = DownloadSecret(
            url = uri.toString(),
            userAgent = userAgent,
            cookie = cookie,
            sourcePage = sourcePage,
        )
        if (!secrets.put(downloadId, secret)) {
            return ManagedDownloadResult.Failed("Gagal menyimpan konteks HLS terenkripsi")
        }

        val now = System.currentTimeMillis()
        return runCatching {
            repository.upsert(
                DownloadEntity(
                    downloadId = downloadId,
                    systemDownloadId = null,
                    url = DownloadPolicy.redactedForStorage(uri),
                    sourceOrigin = sourcePage
                        ?.let(DownloadPolicy::validateHttps)
                        ?.let(DownloadPolicy::originOnly),
                    fileName = fileName,
                    mimeType = "application/vnd.apple.mpegurl",
                    kind = DownloadKinds.HLS_VOD,
                    status = DownloadStatuses.QUEUED,
                    bytesDownloaded = 0,
                    totalBytes = -1,
                    supportsResume = false,
                    etag = null,
                    lastModified = null,
                    sha256 = null,
                    localUri = null,
                    errorCode = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            enqueueWork(downloadId)
            ManagedDownloadResult.Enqueued(
                downloadId = downloadId,
                systemId = -1,
                fileName = fileName,
                supportsResume = false,
            )
        }.getOrElse { error ->
            secrets.remove(downloadId)
            ManagedDownloadResult.Failed(error.message ?: "Gagal membuat antrean HLS")
        }
    }

    suspend fun cancel(downloadId: String): Boolean {
        val item = repository.get(downloadId) ?: return false
        if (item.kind != DownloadKinds.HLS_VOD) return false
        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.CANCELED,
            bytesDownloaded = item.bytesDownloaded,
            totalBytes = item.totalBytes,
            localUri = item.localUri,
            errorCode = null,
        )
        workManager.cancelUniqueWork(workName(downloadId))
        return true
    }

    suspend fun retry(downloadId: String): ManagedDownloadResult {
        val item = repository.get(downloadId)
            ?: return ManagedDownloadResult.Rejected("Metadata HLS tidak ditemukan")
        if (item.kind != DownloadKinds.HLS_VOD) {
            return ManagedDownloadResult.Rejected("Bukan pekerjaan HLS")
        }
        if (item.status !in setOf(
                DownloadStatuses.FAILED,
                DownloadStatuses.CANCELED,
            )
        ) {
            return ManagedDownloadResult.Rejected("HLS belum berada pada status retry")
        }
        if (!secrets.contains(downloadId)) {
            return ManagedDownloadResult.Rejected(
                "Konteks aman HLS tidak tersedia; buat unduhan baru",
            )
        }
        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.QUEUED,
            bytesDownloaded = 0,
            totalBytes = -1,
            localUri = null,
            errorCode = null,
        )
        enqueueWork(downloadId)
        return ManagedDownloadResult.Enqueued(
            downloadId = downloadId,
            systemId = -1,
            fileName = item.fileName,
            supportsResume = false,
        )
    }

    suspend fun deleteMetadata(downloadId: String): Boolean {
        val item = repository.get(downloadId) ?: return false
        if (item.kind != DownloadKinds.HLS_VOD) return false
        workManager.cancelUniqueWork(workName(downloadId))
        secrets.remove(downloadId)
        repository.delete(downloadId)
        return true
    }

    private fun enqueueWork(downloadId: String) {
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
                workDataOf(HlsVodDownloadWorker.KEY_DOWNLOAD_ID to downloadId),
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
