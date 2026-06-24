package com.hereliesaz.graffitixr.common.crash

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Intercepts uncaught exceptions and dumps logs to a file for reporting on next launch.
 */
class CrashReporter(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun initialize() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // ARCore drives its camera through an internal CameraX "camera pipe" (logcat tag CXCP) on its
        // own worker threads. On some devices (e.g. Samsung SM-A236U) a long run of ERROR_CAMERA_DEVICE
        // makes the OEM camera HAL drop the device, and ARCore's pipe then throws
        // "Failed to load metadata for CameraId-N" / "Unable to retrieve camera characteristics"
        // out of getCameraCharacteristics while the session is already tearing down. This lands on an
        // ARCore-managed background thread we cannot wrap in try/catch, so it reaches the default
        // handler and kills the whole app — even though the user has just left AR and nothing in the
        // foreground is broken. Record it for diagnostics, but let the process keep running instead of
        // crashing to a full-screen report. The faulting worker thread is already unwinding; not
        // delegating to the default handler simply restores plain-JVM "only that thread dies" semantics.
        if (thread !== Looper.getMainLooper().thread && isRecoverableArCameraCrash(throwable)) {
            try {
                // fatal = false so CrashUploadWorker files it as a recovered event, not a force-close.
                saveReport(buildReport(throwable, fatal = false))
            } catch (e: Exception) {
                Log.e("CrashReporter", "Failed to save crash report", e)
            }
            Log.w("CrashReporter", "Swallowed recoverable ARCore camera-pipe crash on ${thread.name}", throwable)
            return
        }
        try {
            val report = buildReport(throwable, fatal = true)
            saveReport(report)
        } catch (e: Exception) {
            Log.e("CrashReporter", "Failed to save crash report", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReport(throwable: Throwable, fatal: Boolean): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val stackTrace = Log.getStackTraceString(throwable)
        val logcat = collectLogcat()

        // FATAL must be the first line so consumers (CrashUploadWorker) can classify the report
        // cheaply: true = the process was killed, false = the exception was caught and the app kept
        // running.
        return """
            FATAL: $fatal
            TIMESTAMP: $timestamp
            DEVICE: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})
            VERSION: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}

            STACK TRACE:
            $stackTrace

            LOGCAT:
            $logcat
        """.trimIndent()
    }

    private fun collectLogcat(): String {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1000", "--pid=${android.os.Process.myPid()}"))
            InputStreamReader(process.inputStream).use { it.readText() }
        } catch (e: Exception) {
            "Failed to collect Logcat: ${e.message}"
        } finally {
            // Release the subprocess's pipes/FDs; readText() above already drained stdout.
            process?.destroy()
        }
    }

    private fun saveReport(report: String) {
        val file = File(context.cacheDir, "last_crash.txt")
        FileOutputStream(file).use {
            it.write(report.toByteArray())
        }
    }

    companion object {
        // Substrings that uniquely identify ARCore's camera-pipe "the camera device vanished while we
        // were still using it" failure. Stored lowercase so matching only has to lowercase the
        // message; checked against every message in the throwable's cause + suppressed chain.
        private val CAMERA_PIPE_MESSAGE_SIGNATURES = listOf(
            "failed to load metadata for cameraid",
            "unable to retrieve camera characteristics",
        )

        /**
         * True if [throwable] (or anything in its cause/suppressed chain) is the known ARCore
         * camera-pipe teardown crash — a camera-metadata load failure raised from
         * [android.hardware.camera2.CameraManager.getCameraCharacteristics]. Pure and Android-free so
         * it can be unit-tested. The caller is responsible for restricting recovery to background
         * threads.
         */
        fun isRecoverableArCameraCrash(throwable: Throwable): Boolean {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var matchedMessage = false
            var matchedFrame = false

            fun visit(t: Throwable?) {
                if (t == null || !seen.add(t)) return
                val message = t.message
                if (message != null) {
                    val lower = message.lowercase(Locale.US)
                    if (CAMERA_PIPE_MESSAGE_SIGNATURES.any { lower.contains(it) }) {
                        matchedMessage = true
                    }
                }
                if (t.stackTrace.any {
                        it.className == "android.hardware.camera2.CameraManager" &&
                            it.methodName == "getCameraCharacteristics"
                    }) {
                    matchedFrame = true
                }
                visit(t.cause)
                t.suppressed?.forEach { visit(it) }
            }

            visit(throwable)
            // Require BOTH the camera-metadata message AND the getCameraCharacteristics frame so an
            // unrelated crash that merely mentions one of these strings is never silently swallowed.
            return matchedMessage && matchedFrame
        }
    }
}
