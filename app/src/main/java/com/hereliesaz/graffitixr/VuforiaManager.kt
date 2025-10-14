package com.hereliesaz.graffitixr

import android.app.Activity
import android.opengl.GLSurfaceView
import android.util.Log
import com.hereliesaz.graffitixr.composables.VuforiaRenderer
import com.qualcomm.vuforia.Vuforia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

object VuforiaManager {

    private const val TAG = "VuforiaManager"
    private lateinit var activityRef: WeakReference<Activity>
    private var glSurfaceView: GLSurfaceView? = null
    private var isVuforiaInitialized = false

    fun init(activity: Activity) {
        Log.d(TAG, "init() called")
        activityRef = WeakReference(activity)

        glSurfaceView = GLSurfaceView(activity).apply {
            setEGLContextClientVersion(2)
            setRenderer(VuforiaRenderer(activity))
        }
    }

    fun createEngine(scope: CoroutineScope) {
        if (isVuforiaInitialized) return

        scope.launch(Dispatchers.IO) {
            val activity = activityRef.get() ?: run {
                Log.e(TAG, "Activity is null, cannot create Vuforia engine.")
                return@launch
            }

            val licenseKey = "${BuildConfig.VUFORIA_CLIENT_ID},${BuildConfig.VUFORIA_CLIENT_SECRET}"
            Vuforia.setInitParameters(activity, 0, licenseKey)

            var progress = 0
            do {
                progress = Vuforia.init()
                Log.d(TAG, "Vuforia init progress: $progress%")
            } while (progress >= 0 && progress < 100)

            if (progress < 0) {
                Log.e(TAG, "Vuforia initialization failed with error code: $progress")
            } else {
                isVuforiaInitialized = true
                Log.d(TAG, "Vuforia initialized successfully.")
            }
        }
    }

    fun getGLSurfaceView(): GLSurfaceView? {
        return glSurfaceView
    }

    fun start() {
        if (isVuforiaInitialized) {
            Vuforia.onResume()
        }
    }

    fun stop() {
        if (isVuforiaInitialized) {
            Vuforia.onPause()
        }
    }

    fun deinit() {
        if (isVuforiaInitialized) {
            Vuforia.deinit()
            isVuforiaInitialized = false
        }
        glSurfaceView = null
    }
}
