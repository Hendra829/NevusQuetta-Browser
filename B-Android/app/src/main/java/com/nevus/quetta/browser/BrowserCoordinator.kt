package com.nevus.quetta.browser

import com.nevus.quetta.data.BookmarkEntity
import com.nevus.quetta.data.BrowserRepository
import com.nevus.quetta.data.HistoryEntity
import com.nevus.quetta.data.TabEntity
import com.nevus.quetta.tabs.BrowserTab
import com.nevus.quetta.tabs.TabManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class BrowserCoordinator(
    val tabs: TabManager,
    private val repository: BrowserRepository,
    private val homeUrl: String,
) {
    fun bookmarks(): Flow<List<BookmarkEntity>> = repository.bookmarks()
    fun history(): Flow<List<HistoryEntity>> = repository.history()

    suspend fun restoreSession(fallbackUrl: String?) {
        val saved = repository.tabs().first()
        if (saved.isEmpty()) {
            val initial = fallbackUrl?.takeIf { it.startsWith("https://") } ?: homeUrl
            tabs.reset(initial)
            persistSession()
            return
        }

        val restored = saved.map {
            BrowserTab(
                id = it.tabId,
                url = it.url,
                title = it.title,
                isPrivate = false,
                lastAccessedAt = it.updatedAt,
            )
        }
        val active = saved.firstOrNull { it.isActive }?.tabId
        tabs.restore(restored, active)
    }

    suspend fun newTab(isPrivate: Boolean): BrowserTab? {
        val tab = tabs.newTab(homeUrl, isPrivate) ?: return null
        if (!isPrivate) persistSession()
        return tab
    }

    suspend fun selectTab(id: String): Boolean {
        val changed = tabs.selectTab(id)
        if (changed) persistSession()
        return changed
    }

    suspend fun closeTab(id: String): Boolean {
        val changed = tabs.closeTab(id)
        if (changed) persistSession()
        return changed
    }

    suspend fun pageFinished(id: String, url: String, title: String?) {
        if (!url.startsWith("https://")) return
        val before = tabs.tab(id) ?: return
        tabs.updateNavigation(id, url, title)
        repository.recordVisit(url, title, before.isPrivate)
        if (!before.isPrivate) persistSession()
    }

    suspend fun addBookmark(tabId: String): BookmarkEntity? {
        val tab = tabs.tab(tabId) ?: return null
        if (tab.isPrivate) return null
        return repository.upsertBookmark(tab.url, tab.title)
    }

    suspend fun clearHistory() {
        repository.clearHistory()
    }

    /**
     * Menyimpan URL yang baru dinavigasi ke state tab.
     *
     * Dipakai `MainActivity.navigateOn` agar Activity tidak memutasi `TabManager`
     * secara langsung — satu sumber kebenaran tetap coordinator. Persistensi ke
     * Room sengaja TIDAK dilakukan di sini; itu tugas [pageFinished] setelah halaman
     * benar-benar selesai dimuat, sehingga halaman gagal tidak mengotori riwayat.
     */
    suspend fun navigate(id: String, url: String) {
        if (!url.startsWith("https://")) return
        val before = tabs.tab(id) ?: return
        tabs.updateNavigation(id, url, before.title)
    }

    suspend fun resetSession() {
        tabs.reset(homeUrl)
        persistSession()
    }

    suspend fun persistSession() {
        val state = tabs.state.value
        val persistent = tabs.persistentSnapshot()
        val activePersistentId = state.activeTab
            .takeUnless(BrowserTab::isPrivate)
            ?.id
            ?: persistent.firstOrNull()?.id

        repository.replaceSession(
            persistent.mapIndexed { index, tab ->
                TabEntity(
                    tabId = tab.id,
                    url = tab.url,
                    title = tab.title,
                    isPrivate = false,
                    isActive = tab.id == activePersistentId,
                    position = index,
                    updatedAt = tab.lastAccessedAt,
                )
            },
        )
    }
}
