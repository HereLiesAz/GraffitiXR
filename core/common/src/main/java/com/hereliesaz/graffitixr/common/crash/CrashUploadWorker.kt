package com.hereliesaz.graffitixr.common.crash

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Uploads captured crash reports to GitHub as issues.
 *
 * The report CONTENT is captured by the caller (GraffitiApplication) synchronously at startup, before
 * MainActivity reads + deletes the crash files for its on-screen dialog — otherwise the upload would
 * race that delete (same startup transaction) and usually find nothing. This class only performs the
 * network upload, and never throws (a crash reporter must not crash the app).
 */
class CrashUploadWorker {

    // Built once and reused: recreating Retrofit/OkHttp per upload accumulates thread + connection
    // pools (wasteful, and worse when uploading several crash files in a row). `by lazy` defers
    // creation to the first upload, which runs on Dispatchers.IO — so no work at construction time.
    private val githubService: GitHubCrashService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubCrashService::class.java)
    }

    /**
     * Upload one captured crash [report]. Returns true only on a successful upload. [baseTitle] is the
     * issue title for a fatal crash; a JVM report whose first line is "FATAL: false" (a swallowed /
     * recovered exception) is retitled so it isn't mistaken for a force-close.
     */
    suspend fun uploadCaptured(token: String, baseTitle: String, report: String): Boolean =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) {
                Log.i("CrashUploadWorker", "No crash-report token set; skipping crash upload.")
                return@withContext false
            }
            try {
                uploadToGitHub(report, token, baseTitle)
            } catch (e: Throwable) {
                Log.e("CrashUploadWorker", "Crash upload failed; ignored so startup isn't interrupted", e)
                false
            }
        }

    private suspend fun uploadToGitHub(report: String, token: String, baseTitle: String): Boolean {
        return try {
            val service = githubService
            // JVM reports start with "FATAL: true|false" (see CrashReporter.buildReport). A recovered
            // (non-fatal) report must not masquerade as a force-close. Native reports have no such
            // line, so they keep their baseTitle ("Native Crash").
            val recovered = report.lineSequence().firstOrNull()?.trim() == "FATAL: false"
            val title = if (recovered) "Auto-Report: Recovered Crash (non-fatal)" else baseTitle
            val intro = if (recovered) {
                "A non-fatal exception was caught and the app kept running. Details below:"
            } else {
                "A crash occurred. Details below:"
            }
            val issue = GitHubIssue(
                title = title,
                body = buildIssueBody(intro, report),
            )

            val response = service.createIssue(
                auth = "token $token",
                owner = "HereLiesAz", // Hardcoded for this repo
                repo = "GraffitiXR",
                issue = issue
            )

            if (response.isSuccessful) {
                true
            } else {
                val errorText = response.errorBody()?.use { it.string() }
                Log.e("CrashUploadWorker", "GitHub API Error: ${response.code()} $errorText")
                false
            }
        } catch (e: Exception) {
            Log.e("CrashUploadWorker", "Failed to upload to GitHub", e)
            false
        }
    }

    companion object {
        /**
         * GitHub's maximum issue body length, in characters. A longer body is refused with HTTP 422
         * ("body is too long"), and a JVM report carries up to 1000 lines of logcat, which routinely
         * exceeds it.
         */
        const val GITHUB_BODY_LIMIT = 65_536

        // Headroom for the intro and code fence, and for the limit being counted in a unit other
        // than UTF-16 chars: anything non-BMP in the logcat is two chars here and may count once
        // or twice there.
        private const val BODY_HEADROOM = 1_024
        private const val LOGCAT_MARKER = "\nLOGCAT:\n"

        /** The issue body: [intro] plus [report] in a code block, fitted under [limit]. */
        fun buildIssueBody(intro: String, report: String, limit: Int = GITHUB_BODY_LIMIT): String {
            val open = "$intro\n\n```\n"
            val close = "\n```"
            val budget = (limit - BODY_HEADROOM - open.length - close.length).coerceAtLeast(0)
            return open + fitReport(report, budget) + close
        }

        /**
         * Shorten [report] to at most [maxChars] while keeping what diagnoses a crash: the header
         * (FATAL / device / version) and the stack trace in full when they fit in three quarters of
         * the budget, then the TAIL of the logcat — the lines nearest the crash — with a note of
         * how much was cut. A report with no logcat section (the native backtrace) keeps its head
         * and tail. Unchanged when it already fits.
         */
        fun fitReport(report: String, maxChars: Int): String {
            if (report.length <= maxChars) return report
            if (maxChars <= 0) return ""
            val headCap = maxChars * 3 / 4
            val split = report.indexOf(LOGCAT_MARKER)
            // Head = header + stack trace up to and including the LOGCAT: line; for a report with
            // no logcat section, simply its first three quarters.
            val headEnd = if (split >= 0) split + LOGCAT_MARKER.length else headCap
            var head = report.substring(0, headEnd)
            if (head.length > headCap) {
                // A pathological trace (deep recursion): keep its top, which names the crash site.
                val suffix = if (split >= 0) LOGCAT_MARKER.trimStart('\n') else ""
                val note = "\n[... ${head.length - headCap} characters of the trace omitted ...]\n"
                head = head.substring(0, (headCap - note.length - suffix.length).coerceAtLeast(0)) +
                    note + suffix
            }
            val rest = report.substring(headEnd)
            val room = maxChars - head.length
            if (rest.length <= room) return head + rest
            // Keep the END of the rest (the log lines nearest the crash), starting on a whole line.
            val note = "[... ${rest.length} characters before this point omitted to fit GitHub's limit ...]\n"
            var start = rest.length - (room - note.length).coerceAtLeast(0)
            val nl = rest.indexOf('\n', start)
            if (nl in start until rest.length - 1) start = nl + 1
            val omitted = "[... $start characters before this point omitted to fit GitHub's limit ...]\n"
            return (head + omitted + rest.substring(start)).take(maxChars)
        }
    }
}
