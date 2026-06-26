package com.hereliesaz.graffitixr.common.crash

import android.content.Context
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
class CrashUploadWorker(private val context: Context) {

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
                Log.i("CrashUploadWorker", "No GH_TOKEN; skipping crash upload.")
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
                body = "$intro\n\n```\n$report\n```"
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
}
