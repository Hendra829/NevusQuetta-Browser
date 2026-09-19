package com.nevus.quetta.tabs

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TabManager(
    private val homeUrl: String,
    private val maxTabs: Int = 12,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxTabs >= 1)
    }

    private val initial = newHomeTab()
    private val mutableState = MutableStateFlow(TabState(listOf(initial), initial.id))
    val state: StateFlow<TabState> = mutableState.asStateFlow()

    @Synchronized
    fun newTab(url: String = homeUrl, isPrivate: Boolean = false): BrowserTab? {
        val current = mutableState.value
        if (current.tabs.size >= maxTabs) return null
        val tab = BrowserTab(
            id = idFactory(),
            url = url,
            title = "",
            isPrivate = isPrivate,
            lastAccessedAt = clock(),
        )
        mutableState.value = TabState(current.tabs + tab, tab.id)
        return tab
    }

    @Synchronized
    fun selectTab(id: String): Boolean {
        val current = mutableState.value
        val selected = current.tabs.firstOrNull { it.id == id } ?: return false
        val now = clock()
        mutableState.value = current.copy(
            tabs = current.tabs.map {
                if (it.id == id) selected.copy(lastAccessedAt = now) else it
            },
            activeTabId = id,
        )
        return true
    }

    @Synchronized
    fun updateNavigation(id: String, url: String, title: String?) {
        val current = mutableState.value
        if (current.tabs.none { it.id == id }) return
        mutableState.value = current.copy(
            tabs = current.tabs.map { tab ->
                if (tab.id == id) {
                    tab.copy(
                        url = url,
                        title = title.orEmpty(),
                        lastAccessedAt = clock(),
                    )
                } else {
                    tab
                }
            },
        )
    }

    @Synchronized
    fun closeTab(id: String): Boolean {
        val current = mutableState.value
        val index = current.tabs.indexOfFirst { it.id == id }
        if (index < 0) return false

        val remaining = current.tabs.toMutableList().apply { removeAt(index) }
        if (remaining.isEmpty()) {
            val home = newHomeTab()
            mutableState.value = TabState(listOf(home), home.id)
            return true
        }

        val active = if (current.activeTabId != id) {
            current.activeTabId
        } else {
            remaining[index.coerceAtMost(remaining.lastIndex)].id
        }
        mutableState.value = TabState(remaining, active)
        return true
    }

    @Synchronized
    fun restore(persisted: List<BrowserTab>, preferredActiveId: String? = null) {
        val sanitized = persisted
            .asSequence()
            .filterNot(BrowserTab::isPrivate)
            .filter { it.url.startsWith("https://") }
            .distinctBy(BrowserTab::id)
            .take(maxTabs)
            .toList()

        if (sanitized.isEmpty()) {
            val home = newHomeTab()
            mutableState.value = TabState(listOf(home), home.id)
            return
        }

        val active = preferredActiveId
            ?.takeIf { candidate -> sanitized.any { it.id == candidate } }
            ?: sanitized.first().id
        mutableState.value = TabState(sanitized, active)
    }

    fun persistentSnapshot(): List<BrowserTab> =
        mutableState.value.tabs.filterNot(BrowserTab::isPrivate)

    private fun newHomeTab(): BrowserTab =
        BrowserTab(
            id = idFactory(),
            url = homeUrl,
            title = "",
            isPrivate = false,
            lastAccessedAt = clock(),
        )
}
