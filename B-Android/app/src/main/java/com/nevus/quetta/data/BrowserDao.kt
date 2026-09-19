package com.nevus.quetta.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
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

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM tabs")
    suspend fun clearTabs()

    @Insert
    suspend fun insertTabs(tabs: List<TabEntity>)
}
