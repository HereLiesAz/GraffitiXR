package com.hereliesaz.graffitixr

import android.app.Application

class GraffitiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}
