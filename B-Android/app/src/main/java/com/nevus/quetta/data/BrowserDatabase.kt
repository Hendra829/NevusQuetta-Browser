package com.nevus.quetta.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookmarkEntity::class, HistoryEntity::class, TabEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun browserDao(): BrowserDao

    companion object {
        @Volatile
        private var instance: BrowserDatabase? = null

        fun get(context: Context): BrowserDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BrowserDatabase::class.java,
                    "nevus_browser.db",
                )
                    .build()
                    .also { instance = it }
            }
    }
}
