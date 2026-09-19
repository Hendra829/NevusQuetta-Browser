package com.nevus.quetta.runtime

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevus.quetta.data.BrowserDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserMigrationRuntimeTest {
    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun v1ToV2PreservesUserDataAndAddsDownloads() {
        val name = "nevus-migration-v1-v2.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    url TEXT NOT NULL,
                    normalizedUrl TEXT NOT NULL,
                    title TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_normalizedUrl " +
                    "ON bookmarks(normalizedUrl)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    visitedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_history_visitedAt ON history(visitedAt)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_history_url ON history(url)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tabs (
                    tabId TEXT NOT NULL,
                    url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    isPrivate INTEGER NOT NULL,
                    isActive INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(tabId)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tabs_position ON tabs(position)",
            )

            db.execSQL(
                """
                INSERT INTO bookmarks
                    (url, normalizedUrl, title, createdAt)
                VALUES
                    ('https://example.com/', 'https://example.com/', 'Saved v1', 1000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO history
                    (url, title, visitedAt)
                VALUES
                    ('https://example.org/', 'History v1', 2000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO tabs
                    (tabId, url, title, isPrivate, isActive, position, updatedAt)
                VALUES
                    ('v1-tab', 'https://example.net/', 'Tab v1', 0, 1, 0, 3000)
                """.trimIndent(),
            )
            db.version = 1
        }

        val database = Room.databaseBuilder(
            context,
            BrowserDatabase::class.java,
            name,
        )
            .addMigrations(BrowserDatabase.MIGRATION_1_2)
            .build()

        try {
            runBlocking {
                val dao = database.browserDao()
                val bookmarks = dao.observeBookmarks().first()
                val history = dao.observeHistory().first()
                val tabs = dao.observeTabs().first()
                val downloads = dao.observeDownloads().first()

                assertTrue(bookmarks.any { it.title == "Saved v1" })
                assertTrue(history.any { it.title == "History v1" })
                assertTrue(tabs.any { it.tabId == "v1-tab" && it.isActive })
                assertTrue(downloads.isEmpty())
            }

            database.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='downloads'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("downloads", cursor.getString(0))
            }

            database.openHelper.readableDatabase.query(
                "PRAGMA table_info(downloads)",
            ).use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                }
                assertTrue("etag" in columns)
                assertTrue("lastModified" in columns)
                assertTrue("sha256" in columns)
                assertNotNull(columns)
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }

        println("NEVUS_ROOM_MIGRATION_V1_V2=PASS")
    }
}
