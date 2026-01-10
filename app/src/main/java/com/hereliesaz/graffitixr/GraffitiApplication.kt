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
            // initLocal() is the preferred way for modern OpenCV Android SDKs
            if (OpenCVLoader.initLocal()) {
                Log.d("GraffitiApplication", "OpenCVLoader.initLocal() successful")
            } else {
                Log.w("GraffitiApplication", "OpenCVLoader.initLocal() failed, trying explicit System.loadLibrary...")
                // Fallback to explicit loading if initLocal fails
                System.loadLibrary("opencv_java4")
                Log.d("GraffitiApplication", "System.loadLibrary(opencv_java4) successful")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e("GraffitiApplication", "OpenCV UnsatisfiedLinkError: ${e.message}")
            try {
                // Some versions or builds might have a different library name
                System.loadLibrary("opencv_java")
                Log.d("GraffitiApplication", "System.loadLibrary(opencv_java) successful")
            } catch (e2: UnsatisfiedLinkError) {
                Log.e("GraffitiApplication", "OpenCV loadLibrary failed completely: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.e("GraffitiApplication", "OpenCV initialization exception: ${e.message}")
        }

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}
