package com.nevus.quetta.download

import android.content.Context
import android.os.StatFs
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

sealed interface ManagedDownloadResult {
    data class Enqueued(
        val downloadId: String,
        val systemId: Long,
        val fileName: String,
        val supportsResume: Boolean,
    ) : ManagedDownloadResult

    data class Rejected(val reason: String) : ManagedDownloadResult
    data class Failed(val reason: String) : ManagedDownloadResult
}

class ManagedDownloadCoordinator(
    context: Context,
    private val repository: DownloadRepository,
    private val preflight: DownloadPreflightClient = DownloadPreflightClient(),
    private val secretStore: DownloadSecretStore = DownloadSecretStore(context),
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun observe(): Flow<List<DownloadEntity>> = repository.observe()

    suspend fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        sourcePage: String?,
    ): ManagedDownloadResult {
        val initial = DownloadPolicy.validateHttps(url)
            ?: return ManagedDownloadResult.Rejected("URL unduhan harus HTTPS valid")
        val cookie = if (DownloadPolicy.safeCookieTarget(sourcePage, initial)) {
            CookieManager.getInstance().getCookie(url)
        } else {
            null
        }

        val probe = when (
            val result = preflight.probe(url, userAgent, sourcePage, cookie)
        ) {
            is DownloadProbeResult.Accepted -> result.probe
            is DownloadProbeResult.Rejected ->
                return ManagedDownloadResult.Rejected(result.reason)
        }

        if (!hasStorageFor(probe.contentLength)) {
            return ManagedDownloadResult.Rejected("Ruang penyimpanan tidak mencukupi")
        }

        val downloadId = UUID.randomUUID().toString()
        val existingNames = repository.observe().first().map { it.fileName }.toSet()
        val fileName = uniqueFileName(
            DownloadPolicy.sanitizeFileName(
                probe.suggestedFileName.ifBlank {
                    contentDisposition?.takeIf(String::isNotBlank) ?: "download"
                },
            ),
            existingNames,
            downloadId,
        )

        val secret = DownloadSecret(
            url = probe.finalUrl.toString(),
            userAgent = userAgent,
            cookie = cookie,
            sourcePage = sourcePage,
        )
        if (!secretStore.put(downloadId, secret)) {
            return ManagedDownloadResult.Failed(
                "Metadata rahasia unduhan tidak dapat diamankan",
            )
        }

        val now = System.currentTimeMillis()
        val entity = DownloadEntity(
            downloadId = downloadId,
            systemDownloadId = null,
            url = DownloadPolicy.redactedForStorage(probe.finalUrl),
            sourceOrigin = sourcePage
                ?.let(DownloadPolicy::validateHttps)
                ?.let(DownloadPolicy::originOnly),
            fileName = fileName,
            mimeType = mimeType ?: probe.mimeType,
            kind = DownloadKinds.DIRECT,
            status = DownloadStatuses.QUEUED,
            bytesDownloaded = 0,
            totalBytes = probe.contentLength,
            supportsResume = probe.supportsResume,
            etag = probe.etag,
            lastModified = probe.lastModified,
            sha256 = null,
            localUri = null,
            errorCode = null,
            createdAt = now,
            updatedAt = now,
        )

        return runCatching {
            repository.upsert(entity)
            enqueueWork(downloadId)
            ManagedDownloadResult.Enqueued(
                downloadId = downloadId,
                systemId = -1,
                fileName = fileName,
                supportsResume = probe.supportsResume,
            )
        }.getOrElse { error ->
            runCatching { workManager.cancelUniqueWork(workName(downloadId)) }
            runCatching { repository.delete(downloadId) }
            secretStore.remove(downloadId)
            ManagedDownloadResult.Failed(
                error.message?.take(120) ?: "Gagal membuat antrean unduhan",
            )
        }
    }

    suspend fun pause(downloadId: String): Boolean {
        val item = repository.get(downloadId) ?: return false
        if (item.kind != DownloadKinds.DIRECT ||
            !item.supportsResume ||
            item.status !in setOf(DownloadStatuses.QUEUED, DownloadStatuses.RUNNING)
        ) {
            return false
        }

        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.PAUSED,
            bytesDownloaded = item.bytesDownloaded,
            totalBytes = item.totalBytes,
            localUri = item.localUri,
            errorCode = null,
        )
        workManager.cancelUniqueWork(workName(downloadId))
        return true
    }

    suspend fun cancel(downloadId: String): Boolean {
        val item = repository.get(downloadId) ?: return false
        if (item.kind != DownloadKinds.DIRECT) return false

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
            ?: return ManagedDownloadResult.Rejected("Metadata unduhan tidak ditemukan")
        if (item.kind != DownloadKinds.DIRECT) {
            return ManagedDownloadResult.Rejected("Bukan pekerjaan unduhan langsung")
        }
        if (item.status !in setOf(
                DownloadStatuses.PAUSED,
                DownloadStatuses.FAILED,
                DownloadStatuses.CANCELED,
                DownloadStatuses.MISSING,
            )
        ) {
            return ManagedDownloadResult.Rejected(
                "Unduhan belum berada pada status yang dapat dilanjutkan",
            )
        }
        if (!secretStore.contains(downloadId)) {
            return ManagedDownloadResult.Rejected(
                "Konteks aman unduhan tidak tersedia; buat unduhan baru",
            )
        }

        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.QUEUED,
            bytesDownloaded = item.bytesDownloaded,
            totalBytes = item.totalBytes,
            localUri = null,
            errorCode = null,
        )
        enqueueWork(downloadId)
        return ManagedDownloadResult.Enqueued(
            downloadId = downloadId,
            systemId = -1,
            fileName = item.fileName,
            supportsResume = item.supportsResume,
        )
    }

    suspend fun deleteMetadata(downloadId: String): Boolean {
        val item = repository.get(downloadId) ?: return false
        if (item.kind != DownloadKinds.DIRECT) return false
        workManager.cancelUniqueWork(workName(downloadId))
        secretStore.remove(downloadId)
        partFile(downloadId).delete()
        repository.delete(downloadId)
        return true
    }

    private fun enqueueWork(downloadId: String) {
        val request = OneTimeWorkRequestBuilder<ResumableDownloadWorker>()
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
                workDataOf(ResumableDownloadWorker.KEY_DOWNLOAD_ID to downloadId),
            )
            .addTag(workName(downloadId))
            .build()

        workManager.enqueueUniqueWork(
            workName(downloadId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun hasStorageFor(contentLength: Long): Boolean {
        if (contentLength <= 0) return true
        val available = runCatching {
            StatFs(appContext.filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)
        return available >= contentLength + STORAGE_RESERVE_BYTES
    }

    private fun partFile(downloadId: String): File =
        File(File(appContext.filesDir, PARTS_DIR), "$downloadId.part")

    private fun uniqueFileName(
        desired: String,
        existing: Set<String>,
        downloadId: String,
    ): String {
        if (desired !in existing) return desired
        val dot = desired.lastIndexOf('.')
        val suffix = "-" + downloadId.take(8)
        return if (dot > 0) {
            desired.substring(0, dot) + suffix + desired.substring(dot)
        } else {
            desired + suffix
        }
    }

    private fun workName(downloadId: String): String =
        "nevus-direct-$downloadId"

    private companion object {
        const val PARTS_DIR = "nevus_download_parts"
        const val STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
    }
}
