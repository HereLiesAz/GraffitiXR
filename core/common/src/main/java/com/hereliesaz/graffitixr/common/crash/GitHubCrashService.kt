package com.hereliesaz.graffitixr.common.crash

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

data class GitHubIssue(
    val title: String,
    val body: String,
    val labels: List<String> = listOf("bug", "auto-report")
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
