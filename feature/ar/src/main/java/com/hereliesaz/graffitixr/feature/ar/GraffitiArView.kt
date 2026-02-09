package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

class GraffitiArView(context: Context) : GLSurfaceView(context), DefaultLifecycleObserver {

    private var session: Session? = null
    val arRenderer = ArRenderer(context)

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(arRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    override fun onResume(owner: LifecycleOwner) {
        onResume()
    }

    // Explicitly override onResume from GLSurfaceView
    public override fun onResume() {
        if (session == null) {
            try {
                session = Session(context)
                val config = Config(session)
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.focusMode = Config.FocusMode.AUTO
                session?.configure(config)
                arRenderer.setSession(session!!)
            } catch (e: Exception) {
                Log.e("GraffitiArView", "Failed to create AR Session", e)
            }
        }

        try {
            session?.resume()
            super<GLSurfaceView>.onResume()
        } catch (e: Exception) {
            Log.e("GraffitiArView", "Failed to resume AR Session", e)
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        onPause()
    }

    // Explicitly override onPause from GLSurfaceView
    public override fun onPause() {
        super<GLSurfaceView>.onPause()
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
    }

    fun setShowPointCloud(show: Boolean) {
        arRenderer.showPointCloud = show
    }

    fun setFlashlight(on: Boolean) {
        session?.let { s ->
            val config = s.config
            config.flashMode = if (on) Config.FlashMode.TORCH else Config.FlashMode.OFF
            s.configure(config)
        }
    }

    fun setupAugmentedImageDatabase(db: AugmentedImageDatabase?) {
        session?.let { s ->
            val config = s.config
            if (db != null) {
                config.augmentedImageDatabase = db
            }
            s.configure(config)
        }
    }
}
