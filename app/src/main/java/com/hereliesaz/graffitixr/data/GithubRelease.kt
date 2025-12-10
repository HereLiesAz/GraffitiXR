package com.hereliesaz.graffitixr.data

import kotlinx.serialization.Serializable

@Serializable
data class GithubRelease(
    val tag_name: String,
    val name: String,
    val prerelease: Boolean,
    val html_url: String,
    val created_at: String,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
data class GithubAsset(
    val browser_download_url: String
)
