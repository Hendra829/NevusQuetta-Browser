package com.nevus.quetta.tabs

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String,
    val isPrivate: Boolean,
    val lastAccessedAt: Long,
)

data class TabState(
    val tabs: List<BrowserTab>,
    val activeTabId: String,
) {
    val activeTab: BrowserTab
        get() = tabs.first { it.id == activeTabId }
}
