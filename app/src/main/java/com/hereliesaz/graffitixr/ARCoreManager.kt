package com.hereliesaz.graffitixr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.google.ar.core.exceptions.UnavailableException
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper

class ARCoreManager(private val activity: Activity) : DefaultLifecycleObserver {

    @Volatile
    var session: Session? = null
        private set
    val backgroundRenderer = BackgroundRenderer()
    val pointCloudRenderer = PointCloudRenderer()
    val displayRotationHelper = DisplayRotationHelper(activity)
    private var installRequested = false

    fun onSurfaceCreated() {
        Log.d(TAG, "onSurfaceCreated")
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.d(TAG, "onResume")
        displayRotationHelper.onResume()

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission not granted yet")
            return
        }

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(activity)
                        session?.let {
                            configureSession(it)
                            val filter = CameraConfigFilter(it)
                            val configs = it.getSupportedCameraConfigs(filter)
                            var bestConfig = it.cameraConfig
                            for (config in configs) {
                                if (config.imageSize.width > bestConfig.imageSize.width) {
                                    bestConfig = config
                                }
                            }
                            it.cameraConfig = bestConfig
                        }
                        Log.d(TAG, "Session created and configured")

                        if (isResumed) {
                            try {
                                Log.d(TAG, "Resuming session from onSurfaceCreated")
                                session?.resume()
                            } catch (e: CameraNotAvailableException) {
                                showToast("Camera not available. Please restart the app.")
                                session = null
                                return
                            }
                        }
                    }
                }
            } catch (e: UnavailableException) {
                exception = e
                message = "Failed to create AR session"
            } catch (e: Exception) {
                exception = e
                message = "Failed to create AR session (Unknown error)"
            }

            if (message != null) {
                Log.e(TAG, message, exception)
                showToast("$message: ${exception?.message}")
                return
            }
        }

        try {
            Log.d(TAG, "Resuming session")
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            showToast("Camera not available. Please restart the app.")
            session = null
            return
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Log.d(TAG, "onPause")
        isResumed = false
        session?.pause()
        displayRotationHelper.onPause()
    }

    fun onDrawFrame(width: Int, height: Int): Frame? {
        if (session == null) {
            Log.v(TAG, "onDrawFrame: Session is null")
            return null
        }
        session?.let {
            displayRotationHelper.updateSessionIfNeeded(it)
            it.setCameraTextureName(backgroundRenderer.textureId)
            return try {
                val frame = it.update()
                val camera = frame.camera
                if (camera.trackingState != com.google.ar.core.TrackingState.TRACKING) {
                    Log.w(TAG, "Camera not tracking: ${camera.trackingState}, Reason: ${camera.trackingFailureReason}")
                }
                frame
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available during onDrawFrame", e)
                null
            } catch (e: SessionPausedException) {
                Log.d(TAG, "Session paused, returning null frame")
                null
            }
        }
        return null
    }

    fun updateAugmentedImageDatabase(database: AugmentedImageDatabase) {
        session?.let {
            try {
                it.pause()
                val config = it.config
                config.augmentedImageDatabase = database
                it.configure(config)
                it.resume()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available during session update", e)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        Log.d(TAG, "onDestroy")
        session?.close()
        session = null
    }

    private fun configureSession(session: Session) {
        val config = session.config
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        session.configure(config)
        Log.d(TAG, "Session configured: UpdateMode=${config.updateMode}, PlaneFinding=${config.planeFindingMode}")
    }

    private fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "ARCoreManager"
    }
}
