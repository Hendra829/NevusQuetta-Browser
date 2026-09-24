package com.nevus.quetta.cleanup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class CleanupPolicy(
    val roots: List<File>,
    val olderThanMillis: Long,
    val nowMillis: Long = System.currentTimeMillis(),
    val dryRun: Boolean = false,
)

data class CleanupReport(
    val scanned: Int,
    val eligible: Int,
    val deleted: Int,
    val bytesReclaimed: Long,
    val failures: List<String>,
)

class CleanupManager(
    allowedRoots: Collection<File>,
) {
    private val allowedRoots = allowedRoots
        .mapNotNull { runCatching { it.canonicalFile.toPath() }.getOrNull() }
        .associateBy { it.toString() }

    fun run(policy: CleanupPolicy): CleanupReport {
        var scanned = 0
        var eligible = 0
        var deleted = 0
        var bytesReclaimed = 0L
        val failures = mutableListOf<String>()

        policy.roots.forEach { requested ->
            val root = runCatching { requested.canonicalFile }.getOrElse {
                failures += "invalid-root:" + requested.path
                return@forEach
            }
            if (allowedRoots[root.path] == null) {
                failures += "root-not-allowed:" + root.path
                return@forEach
            }
            val rootPath = runCatching { root.canonicalFile.toPath() }.getOrElse {
                failures += "canonical-root-failed:" + requested.path
                return@forEach
            }
            if (!root.exists() || Files.isSymbolicLink(root.toPath())) return@forEach

            root.listFiles().orEmpty().forEach { child ->
                val result = cleanNode(
                    node = child,
                    root = rootPath,
                    rootFile = root,
                    cutoff = policy.nowMillis - policy.olderThanMillis.coerceAtLeast(0),
                    dryRun = policy.dryRun,
                )
                scanned += result.scanned
                eligible += result.eligible
                deleted += result.deleted
                bytesReclaimed += result.bytesReclaimed
                failures += result.failures
            }
        }

        return CleanupReport(scanned, eligible, deleted, bytesReclaimed, failures)
    }

    private fun cleanNode(
        node: File,
        root: Path,
        rootFile: File,
        cutoff: Long,
        dryRun: Boolean,
    ): CleanupReport {
        if (Files.isSymbolicLink(node.toPath())) {
            return CleanupReport(1, 0, 0, 0, emptyList())
        }

        val canonical = runCatching { node.canonicalFile }.getOrElse {
            return CleanupReport(1, 0, 0, 0, listOf("canonical-failed:" + node.path))
        }
        if (!isInside(canonical, root)) {
            return CleanupReport(1, 0, 0, 0, listOf("outside-root:" + node.path))
        }

        if (canonical.isDirectory) {
            var aggregate = CleanupReport(1, 0, 0, 0, emptyList())
            canonical.listFiles().orEmpty().forEach { child ->
                aggregate += cleanNode(child, root, rootFile, cutoff, dryRun)
            }
            // Folder kosong yang sudah tua ikut dibersihkan (bounded oleh root yang
            // diizinkan). Kegagalan tidak menghentikan proses dan dilaporkan.
            if (!dryRun && aggregate.failures.isEmpty()) {
                val isOld = canonical.lastModified() in 1..cutoff
                if (isOld && canonical.listFiles().isNullOrEmpty()) {
                    runCatching { canonical.delete() }
                        .onFailure { aggregate += CleanupReport(0, 0, 0, 0, listOf("rmdir-failed:" + canonical.path)) }
                }
            }
            return aggregate
        }

        val size = canonical.length().coerceAtLeast(0)
        val isEligible = canonical.isFile && canonical.lastModified() in 1..cutoff
        if (!isEligible) return CleanupReport(1, 0, 0, 0, emptyList())
        if (dryRun) return CleanupReport(1, 1, 0, 0, emptyList())

        return runCatching {
            if (!canonical.delete()) error("delete returned false")
            CleanupReport(1, 1, 1, size, emptyList())
        }.getOrElse {
            CleanupReport(1, 1, 0, 0, listOf("delete-failed:" + canonical.path))
        }
    }

    /**
     * Root sudah dalam bentuk canonical/normalized ([Path.toRealPath]).
     *
     * Bila root dinormalisasi ulang dengan `File.toPath()`, `java.nio` akan
     * mengikuti symlink sehingga pemeriksaan ini bisa dinyatakan lolos untuk
     * berkas yang sebenarnya berada DI LUAR root (regresi yang ditemukan pada
     * audit; lihat AUDIT-REPORT.md A-CL-01). Karena itu root dibiarkan apa
     * adanya dan hanya sisi candidate yang dinormalisasi.
     */
    private fun isInside(candidate: File, root: Path): Boolean {
        val candidatePath = try {
            candidate.toPath().toRealPath()
        } catch (_: IOException) {
            return false
        }
        return candidatePath.startsWith(root) && candidatePath != root
    }

    private operator fun CleanupReport.plus(other: CleanupReport): CleanupReport =
        CleanupReport(
            scanned = scanned + other.scanned,
            eligible = eligible + other.eligible,
            deleted = deleted + other.deleted,
            bytesReclaimed = bytesReclaimed + other.bytesReclaimed,
            failures = failures + other.failures,
        )
}
