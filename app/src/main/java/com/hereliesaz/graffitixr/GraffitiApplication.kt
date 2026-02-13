package com.hereliesaz.graffitixr

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The Application class.
 * Triggers Dagger Hilt code generation and dependency injection initialization.
 */
@HiltAndroidApp
class GraffitiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialization logic handled by Hilt modules.
    }
}
