package com.hereliesaz.graffitixr.common.crash

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The GitHub "create an issue" request body, serialized by Gson (CrashUploadWorker's Retrofit).
 *
 * Gson names JSON keys after the Kotlin field names, and R8 renames fields in release builds — so
 * without [SerializedName] the release app posted `{"a":…,"b":…,"c":…}` and GitHub rejected every
 * crash report for a missing `title`. The annotations pin the wire names; core/common's
 * consumer-rules.pro additionally keeps the fields themselves so R8 can neither rename nor strip
 * them (they are only ever read reflectively).
 */
data class GitHubIssue(
    @SerializedName("title") val title: String,
    @SerializedName("body") val body: String,
    @SerializedName("labels") val labels: List<String> = listOf("bug", "auto-report")
)

interface GitHubCrashService {
    @POST("repos/{owner}/{repo}/issues")
    suspend fun createIssue(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body issue: GitHubIssue
    ): Response<Unit>
}
