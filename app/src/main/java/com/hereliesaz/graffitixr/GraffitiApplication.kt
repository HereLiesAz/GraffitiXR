package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import timber.log.Timber

/**
 * The Application class.
 * Triggers Dagger Hilt code generation and dependency injection initialization.
 * Initializes global libraries like OpenCV and Native Bridge.
 */
@HiltAndroidApp
class GraffitiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 2. Initialize OpenCV
        // CRITICAL: Must be called before any CV-dependent feature (Target Evolution, SLAM)
        if (OpenCVLoader.initDebug()) {
            Timber.d("GraffitiXR: OpenCV loaded successfully.")
        } else {
            Timber.e("GraffitiXR: Could not load OpenCV!")
            // In a production app, you might want to post a global error event here
        }
    }

    companion object {
        init {
            // Load the custom native engine
            try {
                System.loadLibrary("graffitixr")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("GraffitiApplication", "Failed to load native library 'graffitixr'", e)
            }
        }
    }
}