package com.nevus.quetta.cleanup

import java.io.File
import java.nio.file.Files

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
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .associateBy { it.path }

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
            if (!root.exists() || Files.isSymbolicLink(root.toPath())) return@forEach

            root.listFiles().orEmpty().forEach { child ->
                val result = cleanNode(
                    node = child,
                    root = root,
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
        root: File,
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
                aggregate += cleanNode(child, root, cutoff, dryRun)
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

    private fun isInside(candidate: File, root: File): Boolean {
        val rootPath = root.toPath()
        val candidatePath = candidate.toPath()
        return candidatePath.startsWith(rootPath) && candidatePath != rootPath
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
