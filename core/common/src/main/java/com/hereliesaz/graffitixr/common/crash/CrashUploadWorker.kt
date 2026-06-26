package com.hereliesaz.graffitixr.common.crash

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

/**
 * Checks for saved crash reports and uploads them to GitHub.
 */
class CrashUploadWorker(private val context: Context) {

    suspend fun checkAndUpload(token: String) = withContext(Dispatchers.IO) {
        // This runs at startup (Application.onCreate). It must NEVER throw: an uncaught exception here
        // propagates out of the launching coroutine and force-closes the app ON LAUNCH — and it only
        // runs when a previous crash left last_crash.txt, so the failure would be an occasional
        // launch crash that compounds the very crash it was trying to report. Reading/deleting the
        // file (file IO) and the upload are all wrapped so a crash reporter can never crash the app.
        try {
            val file = File(context.cacheDir, "last_crash.txt")
            if (!file.exists()) return@withContext

            val report = file.readText()
            val success = uploadToGitHub(report, token)

            if (success) {
                file.delete()
                Log.i("CrashUploadWorker", "Crash report uploaded and deleted.")
            }
        } catch (e: Throwable) {
            Log.e("CrashUploadWorker", "checkAndUpload failed; ignoring so startup isn't interrupted", e)
        }
    }

    private suspend fun uploadToGitHub(report: String, token: String): Boolean {
        if (token.isEmpty()) {
            Log.e("CrashUploadWorker", "GH_TOKEN is empty, skipping upload.")
            return false
        }

        return try {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val service = retrofit.create(GitHubCrashService::class.java)
            // The report's first line is "FATAL: true|false" (see CrashReporter.buildReport). A
            // recovered (non-fatal) report — e.g. a swallowed ARCore camera-pipe teardown crash — must
            // not masquerade as a force-close in the issue tracker.
            val recovered = report.lineSequence().firstOrNull()?.trim() == "FATAL: false"
            val issue = GitHubIssue(
                title = if (recovered) "Auto-Report: Recovered Crash (non-fatal)" else "Auto-Report: App Crash",
                body = if (recovered) {
                    "A non-fatal exception was caught and the app kept running. Details below:\n\n```\n$report\n```"
                } else {
                    "A force close occurred. Details below:\n\n```\n$report\n```"
                }
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
