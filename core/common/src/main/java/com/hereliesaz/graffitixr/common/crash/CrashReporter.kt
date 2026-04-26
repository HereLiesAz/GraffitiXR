package com.hereliesaz.graffitixr.common.crash

import android.content.Context
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
        try {
            val report = buildReport(throwable)
            saveReport(report)
        } catch (e: Exception) {
            Log.e("CrashReporter", "Failed to save crash report", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReport(throwable: Throwable): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val stackTrace = Log.getStackTraceString(throwable)
        val logcat = collectLogcat()

        return """
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
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1000", "--pid=${android.os.Process.myPid()}"))
            val reader = InputStreamReader(process.inputStream)
            reader.readText()
        } catch (e: Exception) {
            "Failed to collect Logcat: ${e.message}"
        }
    }

    private fun saveReport(report: String) {
        val file = File(context.cacheDir, "last_crash.txt")
        FileOutputStream(file).use { 
            it.write(report.toByteArray())
        }
    }
}
