package com.nevus.quetta.download

import android.content.Context
import android.net.Uri
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadEntity
import com.nevus.quetta.data.DownloadKinds
import com.nevus.quetta.data.DownloadStatuses
import kotlinx.coroutines.flow.Flow

class DownloadCenter(
    context: Context,
    database: BrowserDatabase = BrowserDatabase.get(context),
) {
    private val repository = DownloadRepository(database)
    private val direct = ManagedDownloadCoordinator(context, repository)
    private val hls = HlsDownloadCoordinator(context, repository)

    fun observe(): Flow<List<DownloadEntity>> = repository.observe()

    suspend fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        sourcePage: String?,
        hint: String? = null,
    ): ManagedDownloadResult {
        return if (isHls(url, mimeType, hint)) {
            hls.enqueue(
                manifestUrl = url,
                userAgent = userAgent,
                sourcePage = sourcePage,
            )
        } else {
            direct.enqueue(
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                sourcePage = sourcePage,
            )
        }
    }

    suspend fun pause(item: DownloadEntity): Boolean =
        if (item.kind == DownloadKinds.DIRECT) {
            direct.pause(item.downloadId)
        } else {
            false
        }

    suspend fun cancel(item: DownloadEntity): Boolean =
        if (item.kind == DownloadKinds.HLS_VOD) {
            hls.cancel(item.downloadId)
        } else {
            direct.cancel(item.downloadId)
        }

    suspend fun retry(item: DownloadEntity): ManagedDownloadResult =
        if (item.kind == DownloadKinds.HLS_VOD) {
            hls.retry(item.downloadId)
        } else {
            direct.retry(item.downloadId)
        }

    suspend fun deleteMetadata(item: DownloadEntity): Boolean =
        if (item.kind == DownloadKinds.HLS_VOD) {
            hls.deleteMetadata(item.downloadId)
        } else {
            direct.deleteMetadata(item.downloadId)
        }

    fun canPause(item: DownloadEntity): Boolean =
        item.kind == DownloadKinds.DIRECT &&
            item.status in setOf(
                DownloadStatuses.QUEUED,
                DownloadStatuses.RUNNING,
            )

    fun canResume(item: DownloadEntity): Boolean =
        item.kind == DownloadKinds.DIRECT &&
            item.status == DownloadStatuses.PAUSED

    fun canRetry(item: DownloadEntity): Boolean =
        item.status in setOf(
            DownloadStatuses.FAILED,
            DownloadStatuses.CANCELED,
            DownloadStatuses.MISSING,
        )

    private fun isHls(url: String, mimeType: String?, hint: String?): Boolean {
        if (hint.equals("hls", ignoreCase = true)) return true
        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (normalizedMime in HLS_MIME_TYPES) return true
        val path = runCatching {
            Uri.parse(url).lastPathSegment.orEmpty().lowercase()
        }.getOrDefault("")
        return path.endsWith(".m3u8")
    }

    private companion object {
        val HLS_MIME_TYPES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
        )
    }
}
