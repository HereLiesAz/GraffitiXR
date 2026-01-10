package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class GraffitiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize OpenCV early to ensure it's available for all threads and activities
        try {
            if (!OpenCVLoader.initLocal()) {
                Log.e("GraffitiApplication", "OpenCVLoader.initLocal() failed, trying loadLibrary...")
                System.loadLibrary("opencv_java4")
            } else {
                Log.d("GraffitiApplication", "OpenCV initialized successfully via initLocal().")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e("GraffitiApplication", "OpenCV UnsatisfiedLinkError: ${e.message}")
            try {
                System.loadLibrary("opencv_java4")
                Log.d("GraffitiApplication", "OpenCV loaded via System.loadLibrary after initLocal failure.")
            } catch (e2: UnsatisfiedLinkError) {
                Log.e("GraffitiApplication", "OpenCV loadLibrary failed: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.e("GraffitiApplication", "OpenCV initialization exception: ${e.message}")
        }

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}
