package com.hereliesaz.graffitixr.common.crash

import java.io.File
import java.security.MessageDigest

/**
 * Crash reports waiting to be uploaded, kept until an upload actually succeeds.
 *
 * The crash files themselves (`last_crash.txt`, `last_native_crash.txt`) belong to MainActivity's
 * "previous crash" dialog, which reads and deletes them so the dialog shows exactly once. The
 * uploader used to work from an in-memory copy of those files, so an upload that failed — no
 * network, a GitHub 5xx, a 422 for an over-long body — lost the report for good: the only copy on
 * disk had already been deleted for the dialog. [stage] copies each report here first; [remove] is
 * called only after a successful upload, and anything still queued is retried on the next launch.
 *
 * Entries are named by a hash of their content, so staging the same crash file twice (a second
 * process start before the dialog consumed it) is a no-op rather than a duplicate issue. The queue
 * is bounded by [maxEntries], dropping the oldest. Pure `java.io` so it is unit-testable on the JVM.
 */
class CrashReportQueue(private val dir: File, private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    /** One queued report and the issue title it should be filed under. */
    data class Entry(val file: File, val title: String, val report: String)

    /**
     * Copy each existing source file into the queue under its title. Sources are never modified or
     * deleted. Returns the number of reports newly queued.
     */
    fun stage(sources: List<Pair<File, String>>): Int {
        var added = 0
        for ((source, title) in sources) {
            val report = runCatching { if (source.isFile) source.readText() else null }.getOrNull()
            if (report.isNullOrBlank()) continue
            if (!dir.isDirectory && !dir.mkdirs()) return added
            val target = File(dir, "${hashOf(report)}$SUFFIX")
            if (target.exists()) continue
            // Write-then-rename so a crash mid-write can never leave a half report that later
            // uploads as if it were whole.
            val tmp = File(dir, target.name + ".tmp")
            val ok = runCatching {
                tmp.writeText(TITLE_PREFIX + title.replace('\n', ' ') + "\n" + report)
                tmp.renameTo(target)
            }.getOrDefault(false)
            if (ok) added++ else tmp.delete()
        }
        trim()
        return added
    }

    /** Queued reports, oldest first. Unreadable or malformed entries are dropped. */
    fun pending(): List<Entry> =
        queuedFiles().mapNotNull { f ->
            val text = runCatching { f.readText() }.getOrNull()
            if (text == null || !text.startsWith(TITLE_PREFIX)) {
                f.delete()
                return@mapNotNull null
            }
            val nl = text.indexOf('\n')
            if (nl < 0) {
                f.delete()
                return@mapNotNull null
            }
            Entry(f, text.substring(TITLE_PREFIX.length, nl), text.substring(nl + 1))
        }

    /** Drop one entry — call only after its upload succeeded. */
    fun remove(entry: Entry) {
        entry.file.delete()
    }

    /** Drop everything (consent withdrawn: nothing is kept for an upload that will not happen). */
    fun clear() {
        queuedFiles().forEach { it.delete() }
    }

    private fun queuedFiles(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: emptyArray())
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))

    private fun trim() {
        val files = queuedFiles()
        if (files.size > maxEntries) files.take(files.size - maxEntries).forEach { it.delete() }
    }

    private fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(HASH_CHARS)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5
        private const val SUFFIX = ".crash"
        private const val TITLE_PREFIX = "GXR-ISSUE-TITLE: "
        private const val HASH_CHARS = 24
    }
}
