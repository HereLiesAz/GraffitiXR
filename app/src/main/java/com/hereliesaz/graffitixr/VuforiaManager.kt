package com.hereliesaz.graffitixr

import android.app.Activity
import android.opengl.GLSurfaceView
import android.util.Log
import com.hereliesaz.graffitixr.composables.VuforiaRenderer
import com.qualcomm.vuforia.Vuforia // Reverted to com.qualcomm.vuforia.Vuforia
import java.lang.ref.WeakReference

object VuforiaManager {

    private const val TAG = "VuforiaManager"
    var engine: Long = 0
        private set

    private lateinit var activityRef: WeakReference<Activity>
    private var glSurfaceView: GLSurfaceView? = null

    fun init(activity: Activity) {
        Log.d(TAG, "init() called")
        activityRef = WeakReference(activity)

        // Defer engine creation to the renderer
        glSurfaceView = GLSurfaceView(activity).apply {
            setEGLContextClientVersion(2)
            setRenderer(VuforiaRenderer(activity))
        }
    }

    fun createEngine() {
        val activity = activityRef.get() ?: run {
            Log.e(TAG, "Activity is null, cannot create Vuforia engine.")
            return
        }

        val licenseKey = "${com.hereliesaz.graffitixr.BuildConfig.VUFORIA_CLIENT_ID},${com.hereliesaz.graffitixr.BuildConfig.VUFORIA_CLIENT_SECRET}"
        Vuforia.setInitParameters(activity, 0, licenseKey) // Set license key and other parameters
        Log.d(TAG, "Vuforia license key set via setInitParameters.")

        val configSet = VuforiaJNI.configSetCreate()
        if (configSet == 0L) {
            Log.e(TAG, "Failed to create config set")
            return
        }

        VuforiaJNI.configSetAddPlatformAndroidConfig(configSet, activity)

        engine = VuforiaJNI.engineCreate(configSet)
        if (engine == 0L) {
            Log.e(TAG, "Failed to create Vuforia engine.")
        }

        VuforiaJNI.configSetDestroy(configSet)
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
