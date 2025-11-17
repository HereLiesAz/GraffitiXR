package com.hereliesaz.graffitixr

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Config
import com.google.ar.core.Frame
import android.util.Log
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import java.io.IOException
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat

class ARCoreManager(private val context: Context) : DefaultLifecycleObserver {

    var session: Session? = null
        private set
    val backgroundRenderer = BackgroundRenderer()
    val pointCloudRenderer = PointCloudRenderer()
    val displayRotationHelper = DisplayRotationHelper(context)

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.d(TAG, "onResume")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
            return
        }

        if (session == null) {
            Log.d(TAG, "Session is null, creating a new one")
            try {
                val installStatus = ArCoreApk.getInstance().requestInstall(context as android.app.Activity, true)
                when (installStatus) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(context)
                        Log.d(TAG, "Session created")
                    }
                    else -> {
                        Toast.makeText(context, "ARCore installation required.", Toast.LENGTH_LONG).show()
                        return
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to create AR session: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        session?.let {
            val config = Config(it)
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            it.configure(config)
            Log.d(TAG, "Session configured")
        }

        try {
            session?.let {
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
            Log.d(TAG, "Resuming session")
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(context, "Camera not available. Please restart the app.", Toast.LENGTH_LONG).show()
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

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        Log.d(TAG, "onDestroy")
        session?.close()
        session = null
    }

    companion object {
        private const val TAG = "ARCoreManager"
    }
}
