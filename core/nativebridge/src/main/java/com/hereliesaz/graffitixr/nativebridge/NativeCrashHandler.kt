package com.hereliesaz.graffitixr.nativebridge

/**
 * Installs a native (C++) signal handler that captures a backtrace to [path] when the process
 * dies from SIGSEGV/SIGABRT/etc. — crashes the JVM UncaughtExceptionHandler can't see. The handler
 * chains to the previous one so the normal tombstone still happens. The file is surfaced on the
 * next app launch. Requires the native library to already be loaded.
 */
object NativeCrashHandler {
    @Volatile private var installed = false

    fun install(path: String) {
        if (installed) return
        try {
            nativeInstall(path)
            installed = true
        } catch (t: Throwable) {
            // Native lib not loaded / symbol missing — non-fatal; we just won't capture native crashes.
        }
    }

    private external fun nativeInstall(path: String)
}
