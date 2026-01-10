package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class GraffitiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Load OpenCV as early as possible to ensure native libraries are available for all threads
        Log.d("GraffitiApplication", "Initializing OpenCV...")
        try {
            // initLocal() is the preferred way for modern OpenCV Android SDKs (4.5+)
            if (OpenCVLoader.initLocal()) {
                Log.i("GraffitiApplication", "OpenCVLoader.initLocal() successful")
            } else {
                Log.w("GraffitiApplication", "OpenCVLoader.initLocal() failed, trying explicit System.loadLibrary...")
                // Fallback to explicit loading if initLocal fails
                loadOpenCVExplicitly()
            }
        } catch (e: Exception) {
            Log.e("GraffitiApplication", "OpenCV initialization exception: ${e.message}", e)
            loadOpenCVExplicitly()
        }

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }

    private fun loadOpenCVExplicitly() {
        try {
            System.loadLibrary("opencv_java4")
            Log.i("GraffitiApplication", "System.loadLibrary(opencv_java4) successful")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("GraffitiApplication", "opencv_java4 not found, trying opencv_java")
            try {
                System.loadLibrary("opencv_java")
                Log.i("GraffitiApplication", "System.loadLibrary(opencv_java) successful")
            } catch (e2: UnsatisfiedLinkError) {
                Log.e("GraffitiApplication", "OpenCV loadLibrary failed completely: ${e2.message}")
            }
        }
    }
}
