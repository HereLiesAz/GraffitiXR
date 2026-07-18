package com.hereliesaz.graphixr

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * GraphiXR's Application — the sketching & photo-editing companion to GraffitiXR. Triggers Hilt
 * code generation. Deliberately lean: no AR/SLAM, no co-op, no crash-upload worker.
 *
 * (The shared editor's native library loading is wired in once the editor screen is added; this
 * skeleton commit only establishes a buildable second-app module.)
 */
@HiltAndroidApp
class GraphixrApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
