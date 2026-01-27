package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class GraffitiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Load OpenCV early. We do this in onCreate rather than a static block
        // to ensure the application context and native library paths are fully initialized.
        Log.d(TAG, "Initializing OpenCV...")
        initializeOpenCV()

        // Initialize Crash Handler
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }

    private fun initializeOpenCV() {
        val isLoaded = try {
            // initLocal() is the standard for OpenCV 4.5+ (static linkage)
            if (OpenCVLoader.initLocal()) {
                Log.i(TAG, "OpenCVLoader.initLocal() successful")
                true
            } else {
                Log.w(TAG, "OpenCVLoader.initLocal() failed, attempting system load...")
                loadOpenCVExplicitly()
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV initialization exception", e)
            loadOpenCVExplicitly()
        }

        if (!isLoaded) {
            Log.e(TAG, "CRITICAL: OpenCV failed to load. Computer Vision features will be unavailable.")
        }
    }

    private fun loadOpenCVExplicitly(): Boolean {
        return try {
            System.loadLibrary("opencv_java4")
            Log.i(TAG, "System.loadLibrary(opencv_java4) successful")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "opencv_java4 not found, trying legacy opencv_java...")
            try {
                System.loadLibrary("opencv_java")
                Log.i(TAG, "System.loadLibrary(opencv_java) successful")
                true
            } catch (e2: UnsatisfiedLinkError) {
                Log.e(TAG, "FATAL: Could not load any OpenCV library.", e2)
                false
            }
        }
    }

    companion object {
        private const val TAG = "GraffitiApplication"
    }
}
