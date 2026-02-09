package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
// FIXED IMPORT:
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

class GraffitiArView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var session: Session? = null
    private val renderer: ArRenderer

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)

        renderer = ArRenderer(context)
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    fun setSession(arSession: Session) {
        this.session = arSession
        val config = arSession.config
        config.focusMode = Config.FocusMode.AUTO
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        arSession.configure(config)

        // Pass session to renderer for frame updates
        renderer.setSession(arSession)
    }

    override fun onResume() {
        super.onResume()
        try {
            session?.resume()
        } catch (e: Exception) {
            Log.e("GraffitiArView", "Error resuming AR Session", e)
        }
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
    }

    fun setShowPointCloud(enable: Boolean) {
        renderer.showPointCloud = enable
    }

    fun setFlashlight(enable: Boolean) {
        // Not implemented in renderer yet
    }

    fun setupAugmentedImageDatabase(bitmap: Bitmap?) {
        // Not implemented
    }
}