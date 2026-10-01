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
 * CameraX-backed transparent `GLSurfaceView.Renderer` shared by standalone SphereSLAM AR and the
 * legacy homography Overlay tracker. It draws [OverlayRenderer]'s design quad from the view/projection
 * pair supplied by the active tracker above [com.hereliesaz.graffitixr.feature.ar.CameraPreview] —
 * the same
 * `setZOrderMediaOverlay(true)` + `PixelFormat.TRANSLUCENT` pattern `MainScreen.kt` already uses
 * to layer AR mode's GL surface, just with CameraX supplying the camera image underneath instead
 * of ARCore drawing its own camera background.
 *
 * Poses and the design texture arrive from other threads (the CameraX analyzer thread, the UI
 * thread) while `onDrawFrame` runs on the GL thread — every cross-thread field here is an
 * [AtomicReference] or `@Volatile`, published as one immutable snapshot per update, the same
 * reasoning [com.hereliesaz.graffitixr.feature.ar.DeviceAttitudeProvider] documents for its own
 * fields: a reader must never see a half-written pose.
 *
 * **Viewport matches the preview's letterbox, not the raw GL surface.** [projMatrix] is solved
 * against the tracked camera frame's own pixel aspect ratio, but this GL surface is stretched to
 * fill its parent `Box` — usually a different aspect than the frame. Drawing into the full surface
 * under those two different aspects would stretch/mis-scale the design relative to what
 * `CameraPreview`'s `PreviewView` is actually showing beneath it, so [onDrawFrame] confines drawing
 * to a centered, aspect-correct sub-rectangle of the surface — the exact `FIT_CENTER` scale-to-fit
 * math `CameraPreview` uses for its own `PreviewView.ScaleType`, computed independently here since
 * a `GLSurfaceView` has no such built-in mode.
 */
internal sealed interface TextureUpdateCommand {
    data class Replace(val bitmap: Bitmap) : TextureUpdateCommand
    data object Clear : TextureUpdateCommand
}

/** Single-slot, last-command-wins mailbox between UI and GL threads. */
internal class TextureUpdateMailbox {
    private val pending = AtomicReference<TextureUpdateCommand?>(null)

    fun replace(bitmap: Bitmap) {
        pending.set(TextureUpdateCommand.Replace(bitmap))
    }

    fun clear() {
        pending.set(TextureUpdateCommand.Clear)
    }

    fun take(): TextureUpdateCommand? = pending.getAndSet(null)
}

class HomographyOverlayRenderer(context: Context) : android.opengl.GLSurfaceView.Renderer {

    /** One pose + the projection it was computed against, published together so they can't tear. */
    data class Frame(val viewMatrix: FloatArray, val projMatrix: FloatArray, val frameAspect: Float)

    private val overlayRenderer = OverlayRenderer(context)

    private val latestFrame = AtomicReference<Frame?>(null)
    private val textureUpdates = TextureUpdateMailbox()
    @Volatile private var extentHalfW = 0.5f
    @Volatile private var extentHalfH = 0.5f
    @Volatile private var extentDirty = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    // Whole-design transform. Defaults preserve the existing homography-fallback behavior.
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var designScale = 1f
    @Volatile private var rotationZDeg = 0f
    @Volatile private var rotationXDeg = 0f
    @Volatile private var rotationYDeg = 0f

    private val model4 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val contentRotation4 = FloatArray(16)
    private val contentRotationTemp4 = FloatArray(16)
    private val contentRotationMul4 = FloatArray(16)

    /** Push a newly tracked pose — called from any thread (typically the CameraX analyzer thread). */
    fun updatePose(viewMatrix: FloatArray, projMatrix: FloatArray, frameAspect: Float) {
        latestFrame.set(Frame(viewMatrix, projMatrix, frameAspect))
    }

    /** Stop drawing until the next [updatePose] — call when tracking is fully lost. */
    fun clearPose() {
        latestFrame.set(null)
    }

    /** Replace the design texture. Uploaded to GL on the next [onDrawFrame]. Any thread. */
    fun updateDesignBitmap(bitmap: Bitmap) {
        textureUpdates.replace(bitmap)
    }

    /** Clear the design texture on the next GL frame. Any thread. */
    fun clearDesignBitmap() {
        textureUpdates.clear()
    }

    /** The design quad's half-extents — MUST match what was passed to `HomographyArTracker.setReference`. */
    fun setExtent(halfW: Float, halfH: Float) {
        extentHalfW = halfW
        extentHalfH = halfH
        extentDirty = true
    }

    /**
     * Apply the same whole-design geometry used by ARCore mode to this wall-relative renderer.
     * Translation is in the tracker's wall units; Z rotation is OpenGL/right-handed (CCW+).
     */
    fun setTransform(
        panX: Float,
        panY: Float,
        scale: Float,
        rotationZDeg: Float,
        rotationXDeg: Float = 0f,
        rotationYDeg: Float = 0f,
    ) {
        val sanitized = StandaloneRenderTransformSanitizer.sanitize(
            panX = panX,
            panY = panY,
            scale = scale,
            rotationZDeg = rotationZDeg,
            rotationXDeg = rotationXDeg,
            rotationYDeg = rotationYDeg,
        )
        this.panX = sanitized.panX
        this.panY = sanitized.panY
        this.designScale = sanitized.scale
        this.rotationZDeg = sanitized.rotationZDeg
        this.rotationXDeg = sanitized.rotationXDeg
        this.rotationYDeg = sanitized.rotationYDeg
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 0f) // fully transparent — the CameraX preview shows through.
        overlayRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // glClear must hit the FULL surface, not the letterboxed sub-rect below — otherwise a
        // frame that arrives at a new aspect leaves the previous frame's bars undrawn-over.
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (extentDirty) {
            overlayRenderer.setExtent(extentHalfW, extentHalfH)
            extentDirty = false
        }
        when (val textureUpdate = textureUpdates.take()) {
            is TextureUpdateCommand.Replace -> overlayRenderer.updateTexture(textureUpdate.bitmap)
            TextureUpdateCommand.Clear -> overlayRenderer.clearTexture()
            null -> Unit
        }

        val frame = latestFrame.get() ?: return
        val viewport = letterboxViewport(surfaceWidth, surfaceHeight, frame.frameAspect)
        if (viewport != null) GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])

        Matrix.setIdentityM(model4, 0)
        Matrix.translateM(model4, 0, panX, panY, 0f)
        Matrix.rotateM(model4, 0, rotationZDeg, 0f, 0f, 1f)
        Matrix.scaleM(model4, 0, designScale, designScale, 1f)
        overlayRenderer.draw(
            frame.viewMatrix,
            frame.projMatrix,
            model4,
            contentRotation = buildContentRotation(rotationXDeg, rotationYDeg),
        )
    }

    private fun buildContentRotation(rx: Float, ry: Float): FloatArray? {
        if (rx == 0f && ry == 0f) return null
        val d = OverlayRenderer.QUAD_HALF_EXTENT * 3.0f

        Matrix.setIdentityM(contentRotation4, 0)
        if (ry != 0f) {
            val rad = Math.toRadians(ry.toDouble())
            contentRotation4[0] = kotlin.math.cos(rad).toFloat()
            contentRotation4[3] = kotlin.math.sin(rad).toFloat() / d
        }

        if (rx != 0f) {
            val rad = Math.toRadians(rx.toDouble())
            Matrix.setIdentityM(contentRotationTemp4, 0)
            contentRotationTemp4[5] = kotlin.math.cos(rad).toFloat()
            contentRotationTemp4[7] = kotlin.math.sin(rad).toFloat() / d
            Matrix.multiplyMM(
                contentRotationMul4, 0, contentRotation4, 0, contentRotationTemp4, 0,
            )
            System.arraycopy(contentRotationMul4, 0, contentRotation4, 0, 16)
        }
        return contentRotation4
    }

    /** Deletes every GL object this owns. Must run on the GL thread (e.g. via `view.queueEvent`). */
    fun release() {
        overlayRenderer.release()
    }
}

/**
 * `FIT_CENTER`: scale [frameAspect] (width/height) to fit fully inside a [surfaceWidth]x[surfaceHeight]
 * surface, centered — matching `PreviewView.ScaleType.FIT_CENTER` pixel-for-pixel. Returns
 * `[x, y, width, height]` for `glViewport`, or null when there's nothing sane to compute (a surface
 * or aspect that hasn't been set up yet). A plain function, not a method, so it's testable without
 * an EGL context — see [HomographyOverlayRenderer]'s doc for why this match matters.
 */
internal fun letterboxViewport(surfaceWidth: Int, surfaceHeight: Int, frameAspect: Float): IntArray? {
    if (surfaceWidth <= 0 || surfaceHeight <= 0 || frameAspect <= 0f) return null
    val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
    val vpW: Int
    val vpH: Int
    if (frameAspect > surfaceAspect) {
        vpW = surfaceWidth
        vpH = (surfaceWidth / frameAspect).toInt()
    } else {
        vpH = surfaceHeight
        vpW = (surfaceHeight * frameAspect).toInt()
    }
    return intArrayOf((surfaceWidth - vpW) / 2, (surfaceHeight - vpH) / 2, vpW, vpH)
}
