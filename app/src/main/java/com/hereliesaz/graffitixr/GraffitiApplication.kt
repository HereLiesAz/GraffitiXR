package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class GraffitiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Load OpenCV as early as possible to ensure native libraries are available for all threads
        Log.d("GraffitiApplication", "Initializing OpenCV...")
        val isLoaded = try {
            // initLocal() is the standard way for modern OpenCV Android SDKs (4.5+)
            if (OpenCVLoader.initLocal()) {
                Log.i("GraffitiApplication", "OpenCVLoader.initLocal() successful")
                true
            } else {
                Log.w("GraffitiApplication", "OpenCVLoader.initLocal() failed, trying explicit System.loadLibrary...")
                loadOpenCVExplicitly()
            }
        } catch (e: Exception) {
            Log.e("GraffitiApplication", "OpenCV initialization exception: ${e.message}", e)
            loadOpenCVExplicitly()
        }

        if (!isLoaded) {
            Log.e("GraffitiApplication", "CRITICAL: OpenCV failed to load. AR fingerprinting and SLAM features will be disabled.")
        }

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }

    private fun loadOpenCVExplicitly(): Boolean {
        return try {
            // Try loading the library by its common names
            System.loadLibrary("opencv_java4")
            Log.i("GraffitiApplication", "System.loadLibrary(opencv_java4) successful")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("GraffitiApplication", "opencv_java4 not found, trying opencv_java")
            try {
                System.loadLibrary("opencv_java")
                Log.i("GraffitiApplication", "System.loadLibrary(opencv_java) successful")
                true
            } catch (e2: UnsatisfiedLinkError) {
                Log.e("GraffitiApplication", "OpenCV loadLibrary failed completely: ${e2.message}")
                false
            }
        }
    }
}
