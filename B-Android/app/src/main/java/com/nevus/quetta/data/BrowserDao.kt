package com.nevus.quetta.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC, id DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC, id DESC LIMIT :limit")
    fun observeHistory(limit: Int = 500): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM tabs ORDER BY position ASC")
    fun observeTabs(): Flow<List<TabEntity>>

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    suspend fun downloadById(downloadId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE systemDownloadId = :systemId LIMIT 1")
    suspend fun downloadBySystemId(systemId: Long): DownloadEntity?

    @Query("SELECT * FROM bookmarks WHERE normalizedUrl = :normalizedUrl LIMIT 1")
    suspend fun bookmarkByNormalizedUrl(normalizedUrl: String): BookmarkEntity?

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Insert
    suspend fun insertHistory(history: HistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(download: DownloadEntity)

    @Query(
        """
        UPDATE downloads
        SET status = :status,
            bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            localUri = :localUri,
            errorCode = :errorCode,
            updatedAt = :updatedAt
        WHERE downloadId = :downloadId
        """,
    )
    suspend fun updateDownloadProgress(
        downloadId: String,
        status: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        localUri: String?,
        errorCode: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM downloads WHERE downloadId = :downloadId")
    suspend fun deleteDownload(downloadId: String)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM tabs")
    suspend fun clearTabs()

    @Insert
    suspend fun insertTabs(tabs: List<TabEntity>)
}
