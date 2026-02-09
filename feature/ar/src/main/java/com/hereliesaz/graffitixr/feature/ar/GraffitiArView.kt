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
    private val renderer = ArRenderer(context)

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    fun onResume() {
        if (session == null) {
            try {
                session = Session(context)
                val config = Config(session)
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.focusMode = Config.FocusMode.AUTO
                session?.configure(config)
                renderer.setSession(session!!)
            } catch (e: Exception) {
                Log.e("GraffitiArView", "Failed to create AR Session", e)
            }
        }

        try {
            session?.resume()
            super.onResume()
        } catch (e: Exception) {
            Log.e("GraffitiArView", "Failed to resume AR Session", e)
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        onPause()
    }

    fun onPause() {
        super.onPause()
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
    }

    fun setShowPointCloud(show: Boolean) {
        renderer.showPointCloud = show
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
            config.augmentedImageDatabase = db
            s.configure(config)
        }
    }
}
