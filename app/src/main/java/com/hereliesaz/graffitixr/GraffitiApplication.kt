package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

private const val TAG = "GraffitiApp"

@HiltAndroidApp
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
             Log.e(TAG, "OpenCV initialization failed entirely")
        }
    }

    private fun loadOpenCVExplicitly(): Boolean {
        return try {
            System.loadLibrary("opencv_java4")
            Log.i(TAG, "System.loadLibrary(opencv_java4) successful")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load opencv_java4", e)
            false
        }
    }
}
