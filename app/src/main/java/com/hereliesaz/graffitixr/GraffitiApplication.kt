// ~~~ FILE: ./app/src/main/java/com/hereliesaz/graffitixr/GraffitiApplication.kt ~~~
package com.hereliesaz.graffitixr

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import timber.log.Timber

/**
 * The Application class.
 * Triggers Dagger Hilt code generation and dependency injection initialization.
 * Initializes global libraries like OpenCV.
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
        // CRITICAL: We use initLocal() instead of initDebug() to utilize the bundled
        // static OpenCV libraries linked in our C++ core, preventing runtime crashes.
        if (OpenCVLoader.initLocal()) {
            Timber.d("GraffitiXR: OpenCV loaded successfully.")
        } else {
            Timber.e("GraffitiXR: Could not load OpenCV!")
        }
    }

    // NOTE: System.loadLibrary("graffitixr") has been removed from here.
    // Native library loading is strictly managed by the SlamManager singleton
    // to prevent redundant load crashes during Android process death/recreation.
}