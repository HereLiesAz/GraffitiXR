package com.hereliesaz.graffitixr

import android.app.Application
import android.util.Log
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.google.android.gms.security.ProviderInstaller
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import timber.log.Timber
import javax.inject.Inject

/**
 * The Application class.
 * Triggers Dagger Hilt code generation and dependency injection initialization.
 * Initializes global libraries like OpenCV.
 */
@HiltAndroidApp
class GraffitiApplication : Application() {

    @Inject
    lateinit var securityProviderManager: SecurityProviderManager

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

        // 3. Update Security Provider (Fix for SSLHandshakeException)
        // Using explicit listener implementation instead of lambda for clarity/compatibility if needed
        ProviderInstaller.installIfNeededAsync(this, object : ProviderInstaller.ProviderInstallListener {
            override fun onProviderInstalled() {
                Timber.d("GraffitiXR: Security Provider installed successfully.")
            }

            override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: android.content.Intent?) {
                Timber.e("GraffitiXR: Failed to install Security Provider! Error code: $errorCode")
            }
        })
    }

    // NOTE: System.loadLibrary("graffitixr") has been removed from here.
    // Native library loading is strictly managed by the SlamManager singleton
    // to prevent redundant load crashes during Android process death/recreation.
}
