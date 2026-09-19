package com.nevus.quetta.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object DownloadKinds {
    const val DIRECT = "DIRECT"
    const val HLS_VOD = "HLS_VOD"
}

object DownloadStatuses {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELED = "CANCELED"
    const val MISSING = "MISSING"
}

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["updatedAt"]),
        Index(value = ["systemDownloadId"]),
    ],
)
data class DownloadEntity(
    @PrimaryKey val downloadId: String,
    val systemDownloadId: Long?,
    val url: String,
    val sourceOrigin: String?,
    val fileName: String,
    val mimeType: String?,
    val kind: String,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val supportsResume: Boolean,
    val etag: String?,
    val lastModified: String?,
    val sha256: String?,
    val localUri: String?,
    val errorCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
