package com.hereliesaz.graffitixr

import android.app.Activity
import android.opengl.GLSurfaceView
import android.util.Log
import com.hereliesaz.graffitixr.composables.VuforiaRenderer
import java.lang.ref.WeakReference

object VuforiaManager {

    private const val TAG = "VuforiaManager"
    private var engine: Long = 0
    private lateinit var activityRef: WeakReference<Activity>
    private var glSurfaceView: GLSurfaceView? = null

    fun init(activity: Activity) {
        Log.d(TAG, "init() called")
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

        glSurfaceView = GLSurfaceView(activity).apply {
            setEGLContextClientVersion(2)
            setRenderer(VuforiaRenderer(activity))
        }
    }

    fun getEngine(): Long {
        return engine
    }

    fun getGLSurfaceView(): GLSurfaceView? {
        return glSurfaceView
    }

    fun start() {
        Log.d(TAG, "start() called")
        if (engine != 0L) {
            if (!VuforiaJNI.engineStart(engine)) {
                Log.e(TAG, "Failed to start Vuforia engine")
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop() called")
        if (engine != 0L) {
            if (!VuforiaJNI.engineStop(engine)) {
                Log.e(TAG, "Failed to stop Vuforia engine")
            }
        }
    }

    fun deinit() {
        Log.d(TAG, "deinit() called")
        if (engine != 0L) {
            VuforiaJNI.engineDestroy(engine)
            engine = 0
        }
        glSurfaceView = null
    }
}
