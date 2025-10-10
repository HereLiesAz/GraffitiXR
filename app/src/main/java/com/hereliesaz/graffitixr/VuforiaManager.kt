package com.hereliesaz.graffitixr

import android.app.Activity
import android.util.Log
import java.lang.ref.WeakReference

object VuforiaManager {

    private var engine: Long = 0
    private lateinit var activityRef: WeakReference<Activity>

    fun init(activity: Activity) {
        activityRef = WeakReference(activity)
        val configSet = VuforiaJNI.configSetCreate()
        if (configSet == 0L) {
            Log.e("VuforiaManager", "Failed to create config set")
            return
        }

        VuforiaJNI.configSetAddPlatformAndroidConfig(configSet, activity)
        VuforiaJNI.configSetAddLicenseConfig(configSet, BuildConfig.VUFORIA_LICENSE_KEY)

        engine = VuforiaJNI.engineCreate(configSet)
        if (engine == 0L) {
            Log.e("VuforiaManager", "Failed to create Vuforia engine")
        }

        VuforiaJNI.configSetDestroy(configSet)
    }

    fun getEngine(): Long {
        return engine
    }

    fun start() {
        if (engine != 0L) {
            if (!VuforiaJNI.engineStart(engine)) {
                Log.e("VuforiaManager", "Failed to start Vuforia engine")
            }
        }
    }

    fun stop() {
        if (engine != 0L) {
            if (!VuforiaJNI.engineStop(engine)) {
                Log.e("VuforiaManager", "Failed to stop Vuforia engine")
            }
        }
    }

    fun deinit() {
        if (engine != 0L) {
            VuforiaJNI.engineDestroy(engine)
            engine = 0
        }
    }
}
