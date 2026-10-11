package com.hereliesaz.graffitixr.common.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashUploadBodyTest {

    private fun jvmReport(traceLines: Int, logcatLines: Int): String = buildString {
        append("FATAL: true\nTIMESTAMP: 2026-10-11 10:00:00\nDEVICE: Test Phone (Android 15)\nVERSION: 1.0\n\n")
        append("STACK TRACE:\njava.lang.IllegalStateException: boom\n")
        repeat(traceLines) { append("\tat com.example.Frame$it.call(Frame.kt:$it)\n") }
        append("\nLOGCAT:\n")
        repeat(logcatLines) { append("10-11 10:00:00.000  123  456 I Tag: logcat line $it\n") }
        append("LAST-LINE-BEFORE-CRASH")
    }

    @Test
    fun `a report under the limit is untouched`() {
        val report = jvmReport(traceLines = 20, logcatLines = 50)
        val body = CrashUploadWorker.buildIssueBody("intro", report)
        assertTrue(body.contains(report))
    }

    @Test
    fun `an oversized JVM report fits GitHub's limit and keeps the trace and the logcat tail`() {
        val report = jvmReport(traceLines = 40, logcatLines = 5_000)
        assertTrue("fixture must exceed the limit", report.length > CrashUploadWorker.GITHUB_BODY_LIMIT)
        val body = CrashUploadWorker.buildIssueBody("A crash occurred. Details below:", report)
        assertTrue("body ${body.length} > limit", body.length <= CrashUploadWorker.GITHUB_BODY_LIMIT)
        assertTrue(body.startsWith("A crash occurred. Details below:\n\n```\nFATAL: true"))
        assertTrue(body.endsWith("LAST-LINE-BEFORE-CRASH\n```"))
        assertTrue("whole stack trace kept", body.contains("Frame39.call(Frame.kt:39)"))
        assertTrue(body.contains("\nLOGCAT:\n"))
        assertTrue(body.contains("logcat line 4999"))
        assertFalse("oldest logcat dropped", body.contains("logcat line 0\n"))
        assertTrue(body.contains("omitted to fit GitHub's limit"))
        // The first kept logcat line is whole, not a fragment.
        val afterNote = body.substringAfter("omitted to fit GitHub's limit ...]\n")
        assertTrue(afterNote.startsWith("10-11 10:00:00.000"))
    }

    @Test
    fun `a pathological trace is cut from the bottom but still fits with some logcat`() {
        val report = jvmReport(traceLines = 20_000, logcatLines = 2_000)
        val fitted = CrashUploadWorker.fitReport(report, 60_000)
        assertTrue(fitted.length <= 60_000)
        assertTrue(fitted.contains("java.lang.IllegalStateException: boom"))
        assertTrue(fitted.contains("characters of the trace omitted"))
        assertTrue(fitted.endsWith("LAST-LINE-BEFORE-CRASH"))
    }

    @Test
    fun `a report with no logcat section keeps its head and tail`() {
        val native = "*** native crash ***\n" + "backtrace frame\n".repeat(10_000) + "END"
        val fitted = CrashUploadWorker.fitReport(native, 10_000)
        assertTrue(fitted.length <= 10_000)
        assertTrue(fitted.startsWith("*** native crash ***"))
        assertTrue(fitted.endsWith("END"))
    }
}

class CrashReportQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `staged reports survive until removed and the source is left alone`() {
        val cache = tmp.newFolder("cache")
        val src = File(cache, "last_crash.txt").apply { writeText("FATAL: true\nreport body") }
        val queue = CrashReportQueue(File(cache, "queue"))
        assertEquals(1, queue.stage(listOf(src to "Auto-Report: App Crash")))
        assertTrue("source belongs to the dialog; staging must not consume it", src.exists())

        // A failed upload: nothing removed, so a later launch (a new queue object) still sees it.
        val reopened = CrashReportQueue(File(cache, "queue")).pending()
        assertEquals(1, reopened.size)
        assertEquals("Auto-Report: App Crash", reopened[0].title)
        assertEquals("FATAL: true\nreport body", reopened[0].report)

        // Only a success removes it.
        queue.remove(reopened[0])
        assertTrue(queue.pending().isEmpty())
    }

    @Test
    fun `staging the same crash twice queues it once`() {
        val cache = tmp.newFolder("cache")
        val src = File(cache, "last_native_crash.txt").apply { writeText("backtrace") }
        val queue = CrashReportQueue(File(cache, "queue"))
        queue.stage(listOf(src to "Auto-Report: Native Crash"))
        assertEquals(0, queue.stage(listOf(src to "Auto-Report: Native Crash")))
        assertEquals(1, queue.pending().size)
    }

    @Test
    fun `missing or blank sources are skipped, and the queue is bounded`() {
        val cache = tmp.newFolder("cache")
        val queue = CrashReportQueue(File(cache, "queue"), maxEntries = 2)
        assertEquals(0, queue.stage(listOf(File(cache, "absent.txt") to "t")))
        File(cache, "blank.txt").writeText("   ")
        assertEquals(0, queue.stage(listOf(File(cache, "blank.txt") to "t")))
        for (i in 0 until 4) {
            val f = File(cache, "c$i.txt").apply { writeText("crash $i") }
            f.setLastModified(1_000L * i)
            queue.stage(listOf(f to "t$i"))
        }
        assertEquals(2, queue.pending().size)
    }

    @Test
    fun `clear drops everything`() {
        val cache = tmp.newFolder("cache")
        val queue = CrashReportQueue(File(cache, "queue"))
        queue.stage(listOf(File(cache, "a.txt").apply { writeText("a") } to "t"))
        queue.clear()
        assertTrue(queue.pending().isEmpty())
    }
}
