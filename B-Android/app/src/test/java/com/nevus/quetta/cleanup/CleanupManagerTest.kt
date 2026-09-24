package com.nevus.quetta.cleanup

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupManagerTest {
    @Test
    fun `cleanup deletes only old files under explicitly allowed root`() {
        val base = Files.createTempDirectory("nq-clean").toFile()
        val cache = base.resolve("cache").apply { mkdirs() }
        val user = base.resolve("user").apply { mkdirs() }
        val old = cache.resolve("old.tmp").apply { writeText("old") }
        val fresh = cache.resolve("fresh.tmp").apply { writeText("fresh") }
        val keep = user.resolve("bookmark.db").apply { writeText("keep") }
        val now = 10_000L
        Files.setLastModifiedTime(old.toPath(), FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(fresh.toPath(), FileTime.fromMillis(9_500))

        val manager = CleanupManager(listOf(cache))
        val report = manager.run(
            CleanupPolicy(
                roots = listOf(cache, user),
                olderThanMillis = 5_000,
                nowMillis = now,
            ),
        )

        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(keep.exists())
        assertEquals(1, report.deleted)
        assertTrue(report.failures.any { it.startsWith("root-not-allowed:") })
        base.deleteRecursively()
    }

    @Test
    fun `dry run never deletes eligible file`() {
        val cache = Files.createTempDirectory("nq-dry").toFile()
        val file = cache.resolve("old.tmp").apply {
            writeText("old")
            setLastModified(1_000)
        }
        val report = CleanupManager(listOf(cache)).run(
            CleanupPolicy(
                roots = listOf(cache),
                olderThanMillis = 5_000,
                nowMillis = 10_000,
                dryRun = true,
            ),
        )

        assertTrue(file.exists())
        assertEquals(1, report.eligible)
        assertEquals(0, report.deleted)
        cache.deleteRecursively()
    }

    @Test
    fun `symlink is never followed outside cache root`() {
        val base = Files.createTempDirectory("nq-link").toFile()
        val cache = base.resolve("cache").apply { mkdirs() }
        val outside = base.resolve("outside.txt").apply {
            writeText("preserve")
            setLastModified(1_000)
        }
        val link = cache.resolve("escape").toPath()

        runCatching { Files.createSymbolicLink(link, outside.toPath()) }
            .getOrElse {
                base.deleteRecursively()
                return
            }

        CleanupManager(listOf(cache)).run(
            CleanupPolicy(
                roots = listOf(cache),
                olderThanMillis = 5_000,
                nowMillis = 10_000,
            ),
        )

        assertTrue(outside.exists())
        assertTrue(Files.isSymbolicLink(link))
        base.deleteRecursively()
    }
}
