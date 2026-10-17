// FILE: app/src/main/java/com/hereliesaz/graffitixr/GraffitiApplication.kt
package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.google.android.gms.security.ProviderInstaller
import com.hereliesaz.graffitixr.common.crash.CrashReportQueue
import com.hereliesaz.graffitixr.common.crash.CrashReporter
import com.hereliesaz.graffitixr.common.crash.CrashUploadWorker
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
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

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release builds keep only the ARDIAG diagnostic lines (overlay basis, perf, hybrid KPM),
            // so a field report can be captured from the build people actually run. Tagged with the
            // package name so a logcat capture filtered on "graffitixr" picks them up.
            Timber.plant(object : Timber.Tree() {
                override fun isLoggable(tag: String?, priority: Int) = priority >= android.util.Log.INFO
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (message.startsWith("ARDIAG")) android.util.Log.println(priority, "graffitixr", message)
                }
            })
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
        // Auto-report the previous run's crash(es) as a GitHub issue — but ONLY if the artist has
        // explicitly opted in (Settings > Crash reports; off by default). This app's own pitch is
        // "no cloud dependencies, zero data collection" for artists who, per the README, are often
        // doing something illegal; uploading a device model + logcat to a PUBLIC issue tracker
        // without asking is exactly the silent collection that pitch promises doesn't happen.
        // The crash FILES themselves are still written and still read by MainActivity for its
        // on-screen "last run crashed" dialog regardless of this setting — only the network upload
        // is gated. The contents are staged into an upload queue before MainActivity.onCreate reads
        // + deletes the files for that dialog (same startup transaction) — otherwise the async upload
        // races that delete and finds nothing. Then upload off the main thread and drop each queued
        // copy only on success. Covers both the JVM crash dump and the native (SIGSEGV/SIGABRT) backtrace. The
        // benign isolated ":probe" native crash is intentionally NOT reported. The token is the
        // artist's own, entered in Settings and read from the repository below (NEVER compiled into
        // the APK — a build-time token shipped a live credential inside every published binary); a
        // blank token makes uploadCaptured no-op. CoroutineExceptionHandler is belt-and-suspenders:
        // the crash-reporting path must never itself crash the app on launch.
        val crashUploadErrorHandler = CoroutineExceptionHandler { _, e ->
            Log.e("GraffitiApplication", "Crash upload failed at startup; ignored", e)
        }
        // Stage the crash files into a durable upload queue NOW, synchronously, before
        // MainActivity reads + deletes them for its dialog. The queued copy is deleted only after a
        // successful upload, so a failed one (offline, GitHub 5xx) is retried on the next launch
        // instead of being lost with the dialog's copy. Without consent nothing is kept. Main
        // process only: the isolated ":probe" process runs this onCreate too and must not upload.
        if (!currentProcessName().endsWith(":probe")) {
            val crashQueue = CrashReportQueue(java.io.File(cacheDir, "crash-upload-queue"))
            crashQueue.stage(
                listOf(
                    java.io.File(cacheDir, "last_crash.txt") to "Auto-Report: App Crash",
                    java.io.File(cacheDir, "last_native_crash.txt") to "Auto-Report: Native Crash",
                ),
            )
            if (crashQueue.pending().isNotEmpty()) {
                MainScope().launch(Dispatchers.IO + crashUploadErrorHandler) {
                    if (!settingsRepository.crashReportingConsent.first()) {
                        Log.i("GraffitiApplication", "Crash reporting not opted in; not uploading.")
                        crashQueue.clear()
                        return@launch
                    }
                    val crashToken = settingsRepository.crashReportToken.first()
                    if (crashToken.isBlank()) {
                        Log.i("GraffitiApplication", "No crash-report token set; not uploading.")
                        return@launch
                    }
                    val worker = CrashUploadWorker()
                    crashQueue.pending().forEach { entry ->
                        if (worker.uploadCaptured(crashToken, entry.title, entry.report)) {
                            crashQueue.remove(entry)
                        }
                    }
                }
            }
        }

        // 1.3. Non-ARCore (artoolkitX) smoke test. Opt-in via -PposeProbe=true (default off, so this is
        // dead-inert in normal builds). Runs the non-ARCore pose source on its own thread and logs
        // poses (tag POSEPROBE); never touches the ARCore render path. First on-device validation of
        // the artoolkitX integration.
        if (BuildConfig.POSE_FORCE_PROBE) {
            com.hereliesaz.graffitixr.feature.ar.pose.PoseSourceRegistry.probe()
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
