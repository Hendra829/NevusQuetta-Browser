package com.nevus.quetta.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookmarkEntity::class,
        HistoryEntity::class,
        TabEntity::class,
        DownloadEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun browserDao(): BrowserDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads (
                        downloadId TEXT NOT NULL,
                        systemDownloadId INTEGER,
                        url TEXT NOT NULL,
                        sourceOrigin TEXT,
                        fileName TEXT NOT NULL,
                        mimeType TEXT,
                        kind TEXT NOT NULL,
                        status TEXT NOT NULL,
                        bytesDownloaded INTEGER NOT NULL,
                        totalBytes INTEGER NOT NULL,
                        supportsResume INTEGER NOT NULL,
                        localUri TEXT,
                        errorCode TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(downloadId)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads(status)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_downloads_updatedAt ON downloads(updatedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_downloads_systemDownloadId ON downloads(systemDownloadId)",
                )
            }
        }

        @Volatile
        private var instance: BrowserDatabase? = null

        fun get(context: Context): BrowserDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BrowserDatabase::class.java,
                    "nevus_browser.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
