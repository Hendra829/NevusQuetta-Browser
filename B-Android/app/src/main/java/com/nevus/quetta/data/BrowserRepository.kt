package com.nevus.quetta.data

import androidx.room.withTransaction
import com.nevus.quetta.navigation.NavigationController
import com.nevus.quetta.navigation.NavigationTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BrowserRepository(
    private val database: BrowserDatabase,
    private val navigation: NavigationController = NavigationController(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao = database.browserDao()

    fun bookmarks(): Flow<List<BookmarkEntity>> = dao.observeBookmarks()
    fun history(): Flow<List<HistoryEntity>> = dao.observeHistory()
    fun tabs(): Flow<List<TabEntity>> = dao.observeTabs()

    suspend fun upsertBookmark(
        rawUrl: String,
        title: String?,
        createdAt: Long = System.currentTimeMillis(),
    ): BookmarkEntity = withContext(ioDispatcher) {
        val normalized = normalizeWebUrl(rawUrl)
        val existing = dao.bookmarkByNormalizedUrl(normalized)
        val entity = if (existing == null) {
            BookmarkEntity(
                url = normalized,
                normalizedUrl = normalized,
                title = title.orEmpty().ifBlank { normalized },
                createdAt = createdAt,
            ).also { candidate ->
                val id = dao.insertBookmark(candidate)
                return@withContext candidate.copy(id = id)
            }
        } else {
            existing.copy(
                url = normalized,
                title = title.orEmpty().ifBlank { existing.title },
                createdAt = createdAt,
            ).let { updated ->
                dao.updateBookmark(updated)
                updated
            }
        }
        entity
    }

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = withContext(ioDispatcher) {
        dao.deleteBookmark(bookmark)
    }

    suspend fun recordVisit(
        rawUrl: String,
        title: String?,
        isPrivate: Boolean,
        visitedAt: Long = System.currentTimeMillis(),
    ): Boolean = withContext(ioDispatcher) {
        if (isPrivate) return@withContext false
        val normalized = runCatching { normalizeWebUrl(rawUrl) }.getOrElse { return@withContext false }
        dao.insertHistory(
            HistoryEntity(
                url = normalized,
                title = title.orEmpty().ifBlank { normalized },
                visitedAt = visitedAt,
            ),
        )
        true
    }

    suspend fun clearHistory() = withContext(ioDispatcher) {
        dao.clearHistory()
    }

    suspend fun replaceSession(tabs: List<TabEntity>) = withContext(ioDispatcher) {
        val persistent = tabs
            .filterNot(TabEntity::isPrivate)
            .sortedBy(TabEntity::position)
            .mapIndexed { index, tab -> tab.copy(position = index) }

        database.withTransaction {
            dao.clearTabs()
            if (persistent.isNotEmpty()) dao.insertTabs(persistent)
        }
    }

    private fun normalizeWebUrl(raw: String): String {
        return when (val target = navigation.resolve(raw)) {
            is NavigationTarget.Web -> target.uri.toString()
            else -> error("Only web URLs can be persisted")
        }
    }
}
