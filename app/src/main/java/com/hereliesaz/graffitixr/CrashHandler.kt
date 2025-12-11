package com.hereliesaz.graffitixr

import android.app.Application
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class CrashHandler(private val application: Application) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val stringWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stringWriter))
            val stackTrace = stringWriter.toString()

            val intent = Intent(application, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("STACK_TRACE", stackTrace)
                putExtra("EXCEPTION_NAME", throwable.javaClass.simpleName)
            }

            application.startActivity(intent)

            // Kill the process to ensure the state is cleared
            Process.killProcess(Process.myPid())
            exitProcess(2)
        } catch (e: Exception) {
            // If our crash handler crashes, fallback to default
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
