package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(private val context: Context) : GLSurfaceView.Renderer, DefaultLifecycleObserver {

    val view: GLSurfaceView = GLSurfaceView(context).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this@ArRenderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    var session: Session? = null
        private set

    val slamManager = SlamManager()

    // State flags
    private var showPointCloud = false
    private var isFlashlightOn = false

    // Display rotation helper
    private val displayRotationHelper = DisplayRotationHelper(context)

    override fun onResume(owner: LifecycleOwner) {
        onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        onPause()
    }

    fun onResume() {
        if (session == null) {
            try {
                session = Session(context).apply {
                    val config = Config(this)
                    config.focusMode = Config.FocusMode.AUTO
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    configure(config)
                }
            } catch (e: Exception) {
                handleSessionException(e)
                return
            }
        }

        try {
            session?.resume()
            view.onResume()
            displayRotationHelper.onResume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(context, "Camera unavailable", Toast.LENGTH_LONG).show()
        }
    }

    fun onPause() {
        displayRotationHelper.onPause()
        view.onPause()
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Initialize Native Engine (MobileGS)
        slamManager.init(context.assets)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val session = session ?: return

        displayRotationHelper.updateSessionIfNeeded(session)

        try {
            session.setCameraTextureName(slamManager.getExternalTextureId())
            val frame = session.update()
            val camera = frame.camera

            // Pass ARCore frame data to Native Engine
            slamManager.update(
                frame.timestamp,
                camera.displayOrientedPose.translation,
                camera.displayOrientedPose.rotationQuaternion
            )

            // Render
            slamManager.draw(showPointCloud)

        } catch (t: Throwable) {
            // Avoid crashing on render loop errors
            t.printStackTrace()
        }
    }

    // --- Interaction Hooks ---

    fun setShowPointCloud(enable: Boolean) {
        this.showPointCloud = enable
    }

    fun setFlashlight(enable: Boolean) {
        this.isFlashlightOn = enable
        val session = session ?: return
        val config = session.config

        try {
            config.flashMode = if (enable) Config.FlashMode.TORCH else Config.FlashMode.OFF
            session.configure(config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleSessionException(e: Exception) {
        val message = when (e) {
            is UnavailableArcoreNotInstalledException -> "Please install ARCore"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            else -> "Failed to create AR session"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
