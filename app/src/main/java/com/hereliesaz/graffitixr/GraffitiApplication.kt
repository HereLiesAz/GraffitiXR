// FILE: app/src/main/java/com/hereliesaz/graffitixr/GraffitiApplication.kt
package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.google.android.gms.security.ProviderInstaller
import com.hereliesaz.graffitixr.common.crash.CrashReporter
import com.hereliesaz.graffitixr.common.crash.CrashUploadWorker
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * The Application class.
 * Triggers Dagger Hilt code generation and dependency injection initialization.
 * Initializes global libraries.
 */
@HiltAndroidApp
class GraffitiApplication : Application() {

    @Inject
    lateinit var securityProviderManager: SecurityProviderManager

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 1.1. Load Native Libraries (OpenCV + GraffitiXR)
        NativeLibLoader.loadAll()

        // 1.1b. Install native crash capture (signal handler) — catches SIGSEGV/SIGABRT in the
        // AR/SLAM native code, which the JVM CrashReporter below can't see. Surfaced on next launch.
        // The hardware-stereo/depth probe runs in the isolated ":probe" process (which shares this
        // cacheDir); a native crash there is expected on devices with a broken depth graph and is
        // benign (the client times out and falls back to mono). Give it its OWN file so it can never
        // overwrite — or masquerade as — a real main-process crash.
        val crashFileName =
            if (currentProcessName().endsWith(":probe")) "last_native_crash_probe.txt"
            else "last_native_crash.txt"
        com.hereliesaz.graffitixr.nativebridge.NativeCrashHandler.install(
            java.io.File(cacheDir, crashFileName).absolutePath
        )

        // 1.2. Setup Crash Reporting
        CrashReporter(this).initialize()
        // CoroutineExceptionHandler so a failure in the startup crash-upload can never escape this
        // launch and force-close the app on launch (checkAndUpload is also self-guarded). Belt and
        // suspenders: the crash-reporting path must never itself crash the app.
        val crashUploadErrorHandler = CoroutineExceptionHandler { _, e ->
            Log.e("GraffitiApplication", "Crash upload failed at startup; ignored", e)
        }
        MainScope().launch(crashUploadErrorHandler) {
            CrashUploadWorker(this@GraffitiApplication).checkAndUpload(BuildConfig.GH_TOKEN)
        }

        // 2. Update Security Provider (Fix for SSLHandshakeException)
        // Using explicit listener implementation instead of lambda for clarity/compatibility if needed
        ProviderInstaller.installIfNeededAsync(this, object : ProviderInstaller.ProviderInstallListener {
            override fun onProviderInstalled() {
                Timber.d("GraffitiXR: Security Provider installed successfully.")
            }

            override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: android.content.Intent?) {
                Timber.e("GraffitiXR: Failed to install Security Provider! Error code: $errorCode")
            }
        })
    }

    /** Current process name (e.g. "com.hereliesaz.graffitixr" or "...:probe"). Application.getProcessName()
     *  is API 28+, but minSdk is 26, so read /proc/self/cmdline — available on every API level. */
    private fun currentProcessName(): String = try {
        java.io.File("/proc/self/cmdline").readText().takeWhile { it.code != 0 }.trim()
    } catch (_: Exception) {
        ""
    }

    // NOTE: System.loadLibrary("graffitixr") has been removed from here.
    // Native library loading is strictly managed by the SlamManager singleton
    // to prevent redundant load crashes during Android process death/recreation.
}
