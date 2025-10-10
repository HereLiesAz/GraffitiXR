package com.hereliesaz.graffitixr

import android.app.Activity
import com.vuforia.VuforiaJNI

object VuforiaManager {

    fun init(activity: Activity) {
        // The original error shows this is the method signature the native library expects.
        VuforiaJNI.initAR(activity, activity.assets, 0)
    }

    fun start() {
        VuforiaJNI.startAR()
    }

    fun stop() {
        VuforiaJNI.stopAR()
    }

    fun deinit() {
        VuforiaJNI.deinitAR()
    }
}
