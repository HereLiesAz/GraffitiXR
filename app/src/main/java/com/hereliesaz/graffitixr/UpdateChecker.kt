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
sealed class UpdateOffer {
    /** Installed from the Play Store: Play has a newer build (Play reports no version name). */
    object Play : UpdateOffer()

    /** Sideloaded: the latest GitHub release is newer; [versionLabel] is its version name. */
    class GitHub(val versionLabel: String, val url: String) : UpdateOffer()
}

/**
 * Finds the update that matches how the app was installed. A Play install asks Play (the in-app
 * update API); a sideloaded install (a GitHub APK) reads the latest non-prerelease GitHub release,
 * whose body names its `versionCode` (the central release workflow writes "Automated release build —
 * M.m.p.b (versionCode N, sha)"), falling back to the version name. Another store's install is left
 * to that store. Debug builds are never offered one: their version code is 1 and every release
 * would look newer.
 */
object UpdateChecker {
    private const val PLAY_INSTALLER = "com.android.vending"

    // Installs a GitHub release can update: the system package installer (an APK opened from a
    // download), adb, or no recorded installer. Another store's install (Galaxy Store, F-Droid…) is
    // updated by that store, so it is never pointed at GitHub.
    private val SIDELOAD_INSTALLERS = setOf(
        null,
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.shell",
    )
    private const val LATEST_RELEASE = "https://api.github.com/repos/HereLiesAz/GraffitiXR/releases/latest"
    private val VERSION_CODE = Regex("""versionCode\s+(\d+)""")
    private val VERSION_NAME = Regex("""—\s*([0-9][0-9.]*)""")

    suspend fun check(context: Context): UpdateOffer? {
        if (BuildConfig.DEBUG) return null
        return runCatching {
            when (val installer = installer(context)) {
                PLAY_INSTALLER -> checkPlay(context)
                in SIDELOAD_INSTALLERS -> checkGitHub()
                else -> null.also { Timber.i("Installed by %s: its store handles updates", installer) }
            }
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

    private fun installer(context: Context): String? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
    }

    private suspend fun checkPlay(context: Context): UpdateOffer? {
        val info = AppUpdateManagerFactory.create(context).requestAppUpdateInfo()
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return null
        return UpdateOffer.Play
    }

    /** True when dotted version [candidate] is greater than [current] (numeric, part by part). */
    internal fun newerVersionName(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toLongOrNull() ?: 0L }
        val b = current.split('.').map { it.toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0L }
            val y = b.getOrElse(i) { 0L }
            if (x != y) return x > y
        }
        return false
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
            val name = VERSION_NAME.find(body)?.groupValues?.get(1)
            val code = VERSION_CODE.find(body)?.groupValues?.get(1)?.toLongOrNull()
            val newer = when {
                code != null -> code > BuildConfig.VERSION_CODE
                // The release notes changed shape: fall back to the version name, and say so rather
                // than letting update offers quietly stop.
                name != null -> newerVersionName(name, BuildConfig.VERSION_NAME).also {
                    Timber.w("Latest release names no versionCode; compared version names instead")
                }
                else -> {
                    Timber.w("Latest release names neither versionCode nor version; no update offered")
                    false
                }
            }
            if (!newer) return@withContext null
            UpdateOffer.GitHub(name ?: json.optString("tag_name"), json.optString("html_url"))
        } finally {
            conn.disconnect()
        }
    }
}
