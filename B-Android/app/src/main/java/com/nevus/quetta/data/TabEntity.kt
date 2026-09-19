package com.nevus.quetta.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tabs",
    indices = [Index(value = ["position"])],
)
data class TabEntity(
    @PrimaryKey val tabId: String,
    val url: String,
    val title: String,
    val isPrivate: Boolean,
    val isActive: Boolean,
    val position: Int,
    val updatedAt: Long,
)
