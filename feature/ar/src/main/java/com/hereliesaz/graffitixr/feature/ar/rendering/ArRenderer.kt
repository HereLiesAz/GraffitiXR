package com.hereliesaz.graffitixr.feature.ar.rendering

import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renderer for the AR SurfaceView.
 * Coordinate bridge between ARCore/CameraX and the Native MobileGS Engine.
 */
class ArRenderer(
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer, DefaultLifecycleObserver {

    private var viewportWidth = 0
    private var viewportHeight = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        slamManager.initialize()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        slamManager.draw()
    }

    fun updateLightEstimate(intensity: Float) {
        slamManager.updateLight(intensity)
    }

    fun onImageAvailable(image: Image) {
        try {
            slamManager.feedDepthData(image)
        } catch (e: Exception) {
            Log.e("ArRenderer", "Error feeding depth data", e)
        } finally {
            image.close()
        }
    }

    fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray) {
        slamManager.updateCamera(viewMtx, projMtx)
    }

    fun updateMesh(vertices: FloatArray) {
        slamManager.updateMesh(vertices)
    }

    fun alignMap(transform: FloatArray) {
        slamManager.alignMap(transform)
    }

    fun captureKeyframe() {
        slamManager.saveKeyframe()
    }

    fun saveKeyframe(path: String) {
        // Overload if path is needed, or just trigger save
        slamManager.saveKeyframe()
    }

    /**
     * Sets the active overlay image to be projected.
     */
    fun setOverlay(bitmap: Bitmap) {
        // In a real implementation, this would upload the bitmap to a GL Texture
        // via a JNI call. For now, we stub it or pass it if SlamManager supports it.
        // Assuming SlamManager has a texture update method:
        // slamManager.updateOverlayTexture(bitmap)
        Log.d("ArRenderer", "Setting overlay bitmap: ${bitmap.width}x${bitmap.height}")
    }

    // Lifecycle
    override fun onResume(owner: LifecycleOwner) {
        // Native resume logic if needed
    }

    override fun onPause(owner: LifecycleOwner) {
        // Native pause logic if needed
    }
}