package com.hereliesaz.graffitixr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.Dispatchers
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/** A newer build than the one running, and where to get it. */
sealed class UpdateOffer(val versionLabel: String) {
    /** Installed from the Play Store: Play has a newer version code. */
    class Play(versionLabel: String) : UpdateOffer(versionLabel)

    /** Sideloaded: the latest GitHub release carries a newer version code. */
    class GitHub(versionLabel: String, val url: String) : UpdateOffer(versionLabel)
}

/**
 * Finds the update that matches how the app was installed. A Play install asks Play (the in-app
 * update API); anything else (a GitHub APK, a local build) reads the latest non-prerelease GitHub
 * release, whose body names its `versionCode` (the central release workflow writes
 * "Automated release build — M.m.p.b (versionCode N, sha)"). Debug builds are never offered one:
 * their version code is 1 and every release would look newer.
 */
object UpdateChecker {
    private const val PLAY_INSTALLER = "com.android.vending"
    private const val LATEST_RELEASE = "https://api.github.com/repos/HereLiesAz/GraffitiXR/releases/latest"
    private val VERSION_CODE = Regex("""versionCode\s+(\d+)""")
    private val VERSION_NAME = Regex("""—\s*([0-9][0-9.]*)""")

    suspend fun check(context: Context): UpdateOffer? {
        if (BuildConfig.DEBUG) return null
        return runCatching {
            if (installedFromPlay(context)) checkPlay(context) else checkGitHub()
        }.onFailure { Timber.w(it, "Update check failed") }.getOrNull()
    }

    /** Sends the user to the store or release page the [offer] came from. */
    fun open(context: Context, offer: UpdateOffer) {
        val uris = when (offer) {
            is UpdateOffer.Play -> listOf(
                "market://details?id=${context.packageName}",
                "https://play.google.com/store/apps/details?id=${context.packageName}",
            )
            is UpdateOffer.GitHub -> listOf(offer.url)
        }
        for (uri in uris) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun installedFromPlay(context: Context): Boolean {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        return installer == PLAY_INSTALLER
    }

    private suspend fun checkPlay(context: Context): UpdateOffer? {
        val info = AppUpdateManagerFactory.create(context).requestAppUpdateInfo()
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return null
        return UpdateOffer.Play(info.availableVersionCode().toString())
    }

    private suspend fun checkGitHub(): UpdateOffer? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val body = json.optString("body")
            val code = VERSION_CODE.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return@withContext null
            if (code <= BuildConfig.VERSION_CODE) return@withContext null
            val name = VERSION_NAME.find(body)?.groupValues?.get(1) ?: json.optString("tag_name")
            UpdateOffer.GitHub(name, json.optString("html_url"))
        } finally {
            conn.disconnect()
        }
    }
}
