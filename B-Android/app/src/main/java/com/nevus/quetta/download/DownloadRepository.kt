package com.nevus.quetta.download

import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DownloadRepository(
    database: BrowserDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = database.browserDao()

    fun observe(): Flow<List<DownloadEntity>> = dao.observeDownloads()

    suspend fun get(downloadId: String): DownloadEntity? =
        withContext(ioDispatcher) { dao.downloadById(downloadId) }

    suspend fun upsert(entity: DownloadEntity) =
        withContext(ioDispatcher) { dao.upsertDownload(entity) }

    suspend fun updateProgress(
        downloadId: String,
        status: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        localUri: String?,
        errorCode: String?,
        updatedAt: Long = System.currentTimeMillis(),
    ) = withContext(ioDispatcher) {
        dao.updateDownloadProgress(
            downloadId = downloadId,
            status = status,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            localUri = localUri,
            errorCode = errorCode,
            updatedAt = updatedAt,
        )
    }

    suspend fun delete(downloadId: String) =
        withContext(ioDispatcher) { dao.deleteDownload(downloadId) }
}
