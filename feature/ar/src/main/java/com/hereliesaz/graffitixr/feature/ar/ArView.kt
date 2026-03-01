package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArView(
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        slamManager.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        slamManager.draw()
    }

    fun setSourceBitmap(bitmap: Bitmap?) {
        slamManager.setBitmap(bitmap)
    }

    fun processFrame(buffer: ByteBuffer, timestamp: Long, width: Int, height: Int) {
        slamManager.processTeleologicalFrame(buffer, timestamp, width, height)
    }

    /**
     * Should be called when the hosting View or Activity is paused or destroyed.
     */
    fun onDestroy() {
        slamManager.reset()
    }
}
