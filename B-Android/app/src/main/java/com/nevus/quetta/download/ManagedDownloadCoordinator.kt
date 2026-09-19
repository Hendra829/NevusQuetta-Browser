package com.nevus.quetta.download

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.webkit.CookieManager
import com.nevus.quetta.data.DownloadEntity
import com.nevus.quetta.data.DownloadKinds
import com.nevus.quetta.data.DownloadStatuses
import java.util.UUID
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
    private val context: Context,
    private val repository: DownloadRepository,
    private val transport: DownloadCoordinator = DownloadCoordinator(context),
    private val preflight: DownloadPreflightClient = DownloadPreflightClient(),
) {
    private val manager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

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
            DownloadPolicy.sanitizeFileName(probe.suggestedFileName),
            existingNames,
            downloadId,
        )

        val result = transport.enqueue(
            url = probe.finalUrl.toString(),
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType ?: probe.mimeType,
            sourcePage = sourcePage,
            overrideFileName = fileName,
        )

        return when (result) {
            is DownloadResult.Enqueued -> {
                val now = System.currentTimeMillis()
                repository.upsert(
                    DownloadEntity(
                        downloadId = downloadId,
                        systemDownloadId = result.id,
                        url = probe.finalUrl.toString(),
                        sourceOrigin = sourcePage
                            ?.let(DownloadPolicy::validateHttps)
                            ?.let(DownloadPolicy::originOnly),
                        fileName = result.fileName,
                        mimeType = mimeType ?: probe.mimeType,
                        kind = DownloadKinds.DIRECT,
                        status = DownloadStatuses.QUEUED,
                        bytesDownloaded = 0,
                        totalBytes = probe.contentLength,
                        supportsResume = probe.supportsResume,
                        localUri = null,
                        errorCode = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                ManagedDownloadResult.Enqueued(
                    downloadId = downloadId,
                    systemId = result.id,
                    fileName = result.fileName,
                    supportsResume = probe.supportsResume,
                )
            }
            is DownloadResult.Rejected -> ManagedDownloadResult.Rejected(result.reason)
            is DownloadResult.Failed -> ManagedDownloadResult.Failed(result.reason)
        }
    }

    suspend fun cancel(downloadId: String): Boolean {
        val item = repository.get(downloadId) ?: return false
        item.systemDownloadId?.let(manager::remove)
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
        val previous = repository.get(downloadId)
            ?: return ManagedDownloadResult.Rejected("Metadata unduhan tidak ditemukan")
        if (previous.status != DownloadStatuses.FAILED &&
            previous.status != DownloadStatuses.CANCELED &&
            previous.status != DownloadStatuses.MISSING
        ) {
            return ManagedDownloadResult.Rejected("Unduhan belum berada pada status retry")
        }
        return enqueue(
            url = previous.url,
            userAgent = userAgent,
            contentDisposition = null,
            mimeType = previous.mimeType,
            sourcePage = previous.sourceOrigin,
        )
    }

    suspend fun refresh(downloadId: String): DownloadEntity? {
        val item = repository.get(downloadId) ?: return null
        val systemId = item.systemDownloadId ?: return item
        manager.query(DownloadManager.Query().setFilterById(systemId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                if (item.status == DownloadStatuses.COMPLETED &&
                    item.localUri != null
                ) {
                    return item
                }
                repository.updateProgress(
                    downloadId = downloadId,
                    status = DownloadStatuses.MISSING,
                    bytesDownloaded = item.bytesDownloaded,
                    totalBytes = item.totalBytes,
                    localUri = item.localUri,
                    errorCode = "SYSTEM_RECORD_MISSING",
                )
                return repository.get(downloadId)
            }

            val status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
            )
            val bytes = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            val localUri = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
            )
            val reason = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
            )

            val mapped = when (status) {
                DownloadManager.STATUS_PENDING -> DownloadStatuses.QUEUED
                DownloadManager.STATUS_RUNNING -> DownloadStatuses.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatuses.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatuses.COMPLETED
                DownloadManager.STATUS_FAILED -> DownloadStatuses.FAILED
                else -> DownloadStatuses.FAILED
            }

            repository.updateProgress(
                downloadId = downloadId,
                status = mapped,
                bytesDownloaded = bytes.coerceAtLeast(0),
                totalBytes = if (total > 0) total else item.totalBytes,
                localUri = localUri,
                errorCode = if (mapped == DownloadStatuses.FAILED) {
                    "DM_REASON_" + reason
                } else {
                    null
                },
            )
        }
        return repository.get(downloadId)
    }

    suspend fun refreshAll() {
        repository.observe().first()
            .filter { it.systemDownloadId != null }
            .forEach { refresh(it.downloadId) }
    }

    private fun hasStorageFor(contentLength: Long): Boolean {
        if (contentLength <= 0) return true
        val root = Environment.getExternalStorageDirectory()
        val available = runCatching { StatFs(root.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        return available >= contentLength + STORAGE_RESERVE_BYTES
    }

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

    private companion object {
        const val STORAGE_RESERVE_BYTES = 16L * 1024L * 1024L
    }
}
