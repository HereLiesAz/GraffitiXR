package com.hereliesaz.graffitixr

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GraffitiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // RepositoryProvider.initialize(this) -- DELETED.
        // Hilt now handles all data layer initialization.
    }
}