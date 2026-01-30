package com.hereliesaz.graffitixr.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class GithubRelease(
    val tag_name: String,
    val name: String,
    val prerelease: Boolean,
    val html_url: String,
    val created_at: String,
    val assets: List<GithubAsset> = emptyList()
) : Parcelable

@Serializable
@Parcelize
data class GithubAsset(
    val browser_download_url: String
) : Parcelable