// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/HomographyOverlayRenderer.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.Matrix
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * `GLSurfaceView.Renderer` for the ARCore-unavailable fallback: draws
 * [OverlayRenderer]'s design quad, positioned by [com.hereliesaz.graffitixr.feature.ar.
 * BridgedHomographyTracker]'s tracked pose, on a transparent GL surface layered above
 * [com.hereliesaz.graffitixr.feature.ar.CameraPreview]'s CameraX preview — the same
 * `setZOrderMediaOverlay(true)` + `PixelFormat.TRANSLUCENT` pattern `MainScreen.kt` already uses
 * to layer AR mode's GL surface, just with CameraX supplying the camera image underneath instead
 * of ARCore drawing its own camera background.
 *
 * Poses and the design texture arrive from other threads (the CameraX analyzer thread, the UI
 * thread) while `onDrawFrame` runs on the GL thread — every cross-thread field here is an
 * [AtomicReference] or `@Volatile`, published as one immutable snapshot per update, the same
 * reasoning [com.hereliesaz.graffitixr.feature.ar.DeviceAttitudeProvider] documents for its own
 * fields: a reader must never see a half-written pose.
 */
class HomographyOverlayRenderer(context: Context) : android.opengl.GLSurfaceView.Renderer {

    /** One pose + the projection it was computed against, published together so they can't tear. */
    data class Frame(val viewMatrix: FloatArray, val projMatrix: FloatArray)

    private val overlayRenderer = OverlayRenderer(context)

    private val latestFrame = AtomicReference<Frame?>(null)
    private val pendingBitmap = AtomicReference<Bitmap?>(null)
    @Volatile private var extentHalfW = 0.5f
    @Volatile private var extentHalfH = 0.5f
    @Volatile private var extentDirty = false

    private val identity4 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Push a newly tracked pose — called from any thread (typically the CameraX analyzer thread). */
    fun updatePose(viewMatrix: FloatArray, projMatrix: FloatArray) {
        latestFrame.set(Frame(viewMatrix, projMatrix))
    }

    /** Stop drawing until the next [updatePose] — call when tracking is fully lost. */
    fun clearPose() {
        latestFrame.set(null)
    }

    /** Replace the design texture. Uploaded to GL on the next [onDrawFrame]. Any thread. */
    fun updateDesignBitmap(bitmap: Bitmap) {
        pendingBitmap.set(bitmap)
    }

    /** The design quad's half-extents — MUST match what was passed to `HomographyArTracker.setReference`. */
    fun setExtent(halfW: Float, halfH: Float) {
        extentHalfW = halfW
        extentHalfH = halfH
        extentDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 0f) // fully transparent — the CameraX preview shows through.
        overlayRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (extentDirty) {
            overlayRenderer.setExtent(extentHalfW, extentHalfH)
            extentDirty = false
        }
        pendingBitmap.getAndSet(null)?.let { overlayRenderer.updateTexture(it) }

        val frame = latestFrame.get() ?: return
        overlayRenderer.draw(frame.viewMatrix, frame.projMatrix, identity4)
    }

    /** Deletes every GL object this owns. Must run on the GL thread (e.g. via `view.queueEvent`). */
    fun release() {
        overlayRenderer.release()
    }
}
