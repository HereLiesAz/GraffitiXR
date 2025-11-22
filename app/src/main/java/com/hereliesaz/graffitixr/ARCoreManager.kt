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
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper

class ARCoreManager(private val activity: Activity) : DefaultLifecycleObserver {

    var session: Session? = null
        private set
    val backgroundRenderer = BackgroundRenderer()
    val pointCloudRenderer = PointCloudRenderer()
    val displayRotationHelper = DisplayRotationHelper(activity)
    @Volatile
    private var sessionCreated = false

    fun onSurfaceCreated() {
        Log.d(TAG, "onSurfaceCreated")
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
            return
        }

        if (session == null) {
            Log.d(TAG, "Session is null, creating a new one")
            try {
                val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
                when (installStatus) {
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
                        sessionCreated = true
                        Log.d(TAG, "Session created and configured")
                    }
                    else -> {
                        Toast.makeText(activity, "ARCore installation required.", Toast.LENGTH_LONG).show()
                        return
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed to create AR session: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.d(TAG, "onResume")
        if (!sessionCreated) {
            return
        }
        try {
            Log.d(TAG, "Resuming session")
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(activity, "Camera not available. Please restart the app.", Toast.LENGTH_LONG).show()
            session = null
            return
        }
        displayRotationHelper.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Log.d(TAG, "onPause")
        session?.pause()
        displayRotationHelper.onPause()
    }

    fun onDrawFrame(width: Int, height: Int): Frame? {
        if (!sessionCreated) return null
        session?.let {
            displayRotationHelper.updateSessionIfNeeded(it)
            it.setCameraTextureName(backgroundRenderer.textureId)
            return try {
                it.update()
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
        Log.d(TAG, "Session configured")
    }

    companion object {
        private const val TAG = "ARCoreManager"
    }
}
