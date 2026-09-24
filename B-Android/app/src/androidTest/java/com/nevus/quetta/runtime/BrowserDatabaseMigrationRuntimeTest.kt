package com.nevus.quetta.runtime

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nevus.quetta.data.BrowserDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserDatabaseMigrationRuntimeTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-v1-v2.db"

    @Before
    fun cleanBefore() {
        context.deleteDatabase(dbName)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun databaseV1UpgradesToV2WithoutDestroyingUserData() = runBlocking {
        createVersion1Database()

        val room = Room.databaseBuilder(context, BrowserDatabase::class.java, dbName)
            .addMigrations(BrowserDatabase.MIGRATION_1_2)
            .build()
        val sqlite = room.openHelper.writableDatabase

        assertEquals(2, sqlite.version)
        sqlite.query("SELECT COUNT(*) FROM bookmarks").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        sqlite.query("SELECT COUNT(*) FROM history").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        sqlite.query("SELECT COUNT(*) FROM tabs").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        sqlite.query("SELECT COUNT(*) FROM downloads").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        room.close()
    }

    private fun createVersion1Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS bookmarks " +
                            "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "url TEXT NOT NULL, normalizedUrl TEXT NOT NULL, " +
                            "title TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_normalizedUrl " +
                            "ON bookmarks(normalizedUrl)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS history " +
                            "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "url TEXT NOT NULL, title TEXT NOT NULL, visitedAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_history_visitedAt ON history(visitedAt)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_history_url ON history(url)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS tabs " +
                            "(tabId TEXT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, " +
                            "isPrivate INTEGER NOT NULL, isActive INTEGER NOT NULL, " +
                            "position INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                            "PRIMARY KEY(tabId))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_tabs_position ON tabs(position)",
                    )
                    db.execSQL(
                        "INSERT INTO bookmarks(url,normalizedUrl,title,createdAt) " +
                            "VALUES('https://example.com/','https://example.com/','keep',1)",
                    )
                    db.execSQL(
                        "INSERT INTO history(url,title,visitedAt) " +
                            "VALUES('https://example.com/','keep',1)",
                    )
                    db.execSQL(
                        "INSERT INTO tabs(tabId,url,title,isPrivate,isActive,position,updatedAt) " +
                            "VALUES('tab-1','https://example.com/','keep',0,1,0,1)",
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase
        helper.close()
    }
}
