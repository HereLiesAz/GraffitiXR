// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/HomographyOverlayRenderer.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.SystemClock
import com.hereliesaz.sphereslam.CoverageGlowProjection
import com.hereliesaz.sphereslam.PhotosphereMap
import com.hereliesaz.sphereslam.SphereCoverage
import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.camera.CameraFillCenter
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.overlay.CoverageGlowRenderer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * CameraX-backed transparent `GLSurfaceView.Renderer` for standalone SphereSLAM AR (the class name
 * predates the removal of the legacy homography Overlay tracker). It draws [OverlayRenderer]'s design quad from the view/projection
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
 * **Projection matches the preview's `FILL_CENTER` crop, not the tracked frame.** The tracker
 * solves against the full camera frame, but `CameraPreview`'s `PreviewView` fills the screen with a
 * centre crop of it, scaled to cover. Drawing with the frame's own projection would stretch and
 * mis-scale the design relative to what the artist sees, so this renderer takes the frame's
 * intrinsics ([updatePose]) and draws on the FULL surface viewport with a projection rebuilt from
 * those intrinsics rescaled and cropped exactly as the preview is
 * ([CameraFillCenter.projection]; a `GLSurfaceView` has no built-in scale type). The projection is
 * cached per (intrinsics, surface size), so steady tracking allocates nothing per frame.
 *
 * **Optional coverage glow.** Standalone SphereSLAM's freshness wash — SphereSLAM's `:overlay`
 * [CoverageGlowRenderer], passed in as [coverageGlow] — is drawn embedded here
 * ([CoverageGlowRenderer.drawEmbedded]), on the full surface and BEFORE the design quad, so it
 * composites above the camera preview and beneath the artwork. A window-level View cannot do that:
 * this media-overlay surface sits behind the app window, so any View drawn in the window washes over
 * the artwork too. Since SphereSLAM 0.24.3 the glow is
 * re-projected on every draw (display rate) with the full camera rotation (roll included, defined at
 * the poles) from [setAttitude]'s extrapolator at this draw's `elapsedRealtimeNanos`, through the
 * lens of what the screen shows ([setGlowFrameIntrinsics] → [CameraFillCenter.intrinsics], rebuilt
 * here whenever the frame intrinsics or the surface size change). Its user-set colour, intensity,
 * style and the region geometry arrive together through [setCoverageGlowConfig] and are applied on
 * the GL thread before the next draw.
 *
 * **Display-rate artwork pose.** With a [DisplayPosePredictor] attached ([setAttitude]), each draw
 * re-poses the design quad from the newest vision pose rotated by the attitude change since its
 * frame ([ArtworkPosePrediction]), so it follows the device between analyzer updates; without a
 * held prediction it draws the last pushed pose unchanged ([updatePose]).
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

class HomographyOverlayRenderer(
    context: Context,
    /** Embedded coverage wash, drawn under the design; null draws none. */
    private val coverageGlow: CoverageGlowRenderer? = null,
) : android.opengl.GLSurfaceView.Renderer {

    /** One pose + the frame intrinsics it was solved against, published together so they can't tear. */
    data class Frame(val viewMatrix: FloatArray, val frameIntrinsics: CameraIntrinsics)

    private val overlayRenderer = OverlayRenderer(context)

    private val latestFrame = AtomicReference<Frame?>(null)
    private val textureUpdates = TextureUpdateMailbox()
    @Volatile private var extentHalfW = 0.5f
    @Volatile private var extentHalfH = 0.5f
    @Volatile private var extentDirty = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    // GL-thread-only cache of the on-screen projection: rebuilt only when the tracked frame's
    // intrinsics or the surface size change (CameraIntrinsics is a data class, so == is by value).
    private var displayProjectionIntrinsics: CameraIntrinsics? = null
    private var displayProjectionWidth = 0
    private var displayProjectionHeight = 0
    private var displayProjection: FloatArray? = null

    // Whole-design transform. All six components move together, so they are bundled into one immutable
    // snapshot behind an AtomicReference: writing six loose @Volatile fields let the GL thread read a
    // torn set (a new pan with the old scale/rotation) for one frame during a drag. The default is the
    // identity transform.
    private val transform = java.util.concurrent.atomic.AtomicReference(
        StandaloneRenderTransform(
            panX = 0f, panY = 0f, scale = 1f, rotationZDeg = 0f, rotationXDeg = 0f, rotationYDeg = 0f,
        )
    )

    /** Explicit base-map placement of the doodle/artwork plane. Null = no truthful anchor yet. */
    private val mapFromFingerprint =
        java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private val localModel4 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val model4 = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val contentRotation4 = FloatArray(16)
    private val contentRotationTemp4 = FloatArray(16)
    private val contentRotationMul4 = FloatArray(16)

    /**
     * Push a newly tracked pose with the display-upright intrinsics of the full frame it was solved
     * in — called from any thread (typically the CameraX analyzer thread).
     */
    fun updatePose(viewMatrix: FloatArray, frameIntrinsics: CameraIntrinsics) {
        latestFrame.set(Frame(viewMatrix, frameIntrinsics))
    }

    /**
     * Stop drawing until the next [updatePose] — call when tracking is fully lost. Also drops the
     * held display-rate prediction, so the artwork can never be re-posed from a lost lock.
     */
    fun clearPose() {
        latestFrame.set(null)
        attitude.clearPrediction()
    }

    // ---- Attitude: display-rate glow rotation + artwork pose prediction ----------------------

    // GL-free attitude wiring (unit-tested in StandaloneAttitudeWiringTest).
    private val attitude = StandaloneAttitudeWiring()

    // GL-thread scratch for the predicted artwork view.
    private val predictedView = FloatArray(16)

    /**
     * Attach the attitude source (SphereSLAM `CameraAttitudeProvider.attitudeExtrapolator`, i.e.
     * `TYPE_ROTATION_VECTOR`, the sensor that also stamps keyframes) and, optionally, the
     * [DisplayPosePredictor] the tracking session publishes its poses to — which must be fed from
     * the same sensor. Null [extrapolator] leaves the glow's live geometry unprojected (a wash then
     * covers the whole view) and disables prediction. Any thread.
     */
    fun setAttitude(extrapolator: AttitudeExtrapolator?, predictor: DisplayPosePredictor?) {
        attitude.set(extrapolator, predictor)
        coverageGlow?.rotationSource = if (extrapolator == null) {
            null
        } else {
            CoverageGlowRenderer.RotationSource { frameTimeNanos, out ->
                attitude.worldFromCameraAt(frameTimeNanos, out)
            }
        }
    }

    /** The current display rotation in degrees (0/90/180/270); the glow's camera frame. Any thread. */
    fun setDisplayRotation(displayRotationDeg: Int) {
        attitude.setDisplayRotation(displayRotationDeg)
    }

    /**
     * Display-upright intrinsics of the full analysis frame (as tracked), for the glow's lens: the
     * screen shows its `FILL_CENTER` crop. Called on calibration changes and per tracked frame;
     * the lens is rebuilt on the GL thread only when these or the surface size actually change.
     * Any thread.
     */
    fun setGlowFrameIntrinsics(frameIntrinsics: CameraIntrinsics) {
        if (coverageGlow != null && frameIntrinsics.isValid) glowFrameIntrinsics = frameIntrinsics
    }

    @Volatile private var glowFrameIntrinsics: CameraIntrinsics? = null

    // GL-thread cache of the glow lens key.
    private val glowLensCache = GlowLensCache()

    private fun refreshGlowLens(glow: CoverageGlowRenderer, width: Int, height: Int) {
        val shown = glowLensCache.lensIntrinsicsIfChanged(glowFrameIntrinsics, width, height) ?: return
        runCatching { glow.setLens(CoverageGlowProjection.Lens.fromIntrinsics(shown)) }
            .onFailure { timber.log.Timber.w(it, "Rejected coverage glow lens") }
    }

    /** What the glow draws: live photosphere regions, or static NDC triangles. */
    sealed interface CoverageGlowGeometry {
        /**
         * Regions re-projected every draw with the newest rotation
         * (`CoverageGlowRenderer.setRegions`): current tiles for the wash/outline styles, tiles
         * needing an update for "missing tiles only".
         */
        class Regions(val regions: List<SphereCoverage.TileRegion>) : CoverageGlowGeometry

        /** Fixed `GL_TRIANGLES` NDC geometry (`CoverageGlowRenderer.setTriangles`), e.g. the full view. */
        class Triangles(val vertices: FloatArray) : CoverageGlowGeometry
    }

    /**
     * One complete coverage-glow configuration: colour, opacities, edge width, fill mode and the
     * geometry drawn with that mode, applied together on the GL thread so a style switch can never
     * pair the new mode with the old style's geometry (which would invert the glow for a frame).
     */
    class CoverageGlowConfig(
        /** Haze RGB, each in `[0, 1]`. */
        val rgb: FloatArray,
        /** Wash / fill opacity in `[0, 1]`. */
        val alpha: Float,
        /** Edge-glow peak opacity in `[0, 1]` (OUTLINE / COMPLEMENT_OUTLINED). */
        val outlineAlpha: Float,
        /** Edge-glow width in framebuffer pixels (OUTLINE / COMPLEMENT_OUTLINED). */
        val outlineWidthPx: Float,
        val fillMode: CoverageGlowRenderer.FillMode,
        val geometry: CoverageGlowGeometry,
        /**
         * Partly scanned tiles (`PhotosphereMap.regionsInProgress`), drawn over [fillMode] more
         * opaque as each fills (`CoverageGlowRenderer.setRegionProgress`); empty draws none.
         */
        val progress: List<PhotosphereMap.RegionProgress> = emptyList(),
    )

    // Last-wins mailbox; drained on the GL thread right before the glow draws. The library's style
    // setters are individually thread-safe since 0.24.3, but not atomic as a set; applying them all
    // here, between two draws, keeps mode and geometry paired.
    private val pendingGlowConfig = AtomicReference<CoverageGlowConfig?>(null)

    /**
     * Restyle the live coverage glow. Any thread; takes effect on the next GL frame, with no
     * surface or AR restart. No-op when this renderer draws no glow.
     */
    fun setCoverageGlowConfig(config: CoverageGlowConfig) {
        if (coverageGlow != null) pendingGlowConfig.set(config)
    }

    private fun applyPendingGlowConfig(glow: CoverageGlowRenderer) {
        val config = pendingGlowConfig.getAndSet(null) ?: return
        runCatching {
            glow.hazeColor = config.rgb
            glow.hazeAlpha = config.alpha
            glow.outlineAlpha = config.outlineAlpha
            glow.outlineWidthPx = config.outlineWidthPx
            when (val geometry = config.geometry) {
                is CoverageGlowGeometry.Regions -> glow.setRegions(geometry.regions)
                is CoverageGlowGeometry.Triangles -> glow.setTriangles(geometry.vertices)
            }
            glow.setRegionProgress(config.progress)
            glow.fillMode = config.fillMode
        }.onFailure { timber.log.Timber.w(it, "Rejected coverage glow config") }
    }

    /** Replace the design texture. Uploaded to GL on the next [onDrawFrame]. Any thread. */
    fun updateDesignBitmap(bitmap: Bitmap) {
        textureUpdates.replace(bitmap)
    }

    /** Clear the design texture on the next GL frame. Any thread. */
    fun clearDesignBitmap() {
        textureUpdates.clear()
    }

    /** The design quad's half-extents — in the tracked wall units of the reference page. */
    fun setExtent(halfW: Float, halfH: Float) {
        extentHalfW = halfW
        extentHalfH = halfH
        extentDirty = true
    }

    /**
     * Place the doodle-local artwork plane into SphereSLAM's persistent map frame.
     *
     * The renderer fails closed when this is null/invalid; drawing at map origin would silently make
     * the doodle the world frame again.
     */
    fun setMapFromDoodle(value: FloatArray?) {
        mapFromFingerprint.set(
            value?.takeIf { it.size == 16 && it.all(Float::isFinite) }?.copyOf()
        )
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
        transform.set(sanitized)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 0f) // fully transparent — the CameraX preview shows through.
        overlayRenderer.createOnGlThread()
        coverageGlow?.createGlResources()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // One full-surface viewport for the clear, the wash and the design: the FILL_CENTER crop
        // lives in the projection (displayProjectionFor), not in a sub-rectangle.
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        // One time base for this draw: the glow rotation and the artwork prediction both read the
        // attitude at the same instant (elapsedRealtimeNanos, the SensorEvent.timestamp base).
        val nowNs = SystemClock.elapsedRealtimeNanos()
        // Full-surface wash under the artwork; it never clears and restores the GL state it touches.
        coverageGlow?.let { glow ->
            applyPendingGlowConfig(glow)
            refreshGlowLens(glow, surfaceWidth, surfaceHeight)
            glow.drawEmbedded(nowNs)
        }

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
        // No truthful on-screen projection before the surface is sized: draw nothing, not a guess.
        val projection = displayProjectionFor(frame.frameIntrinsics) ?: return

        // The camera pose is camera_from_map. The design therefore needs the explicit
        // map_from_doodle model anchor before its artist-controlled local adjustment.
        val anchor = mapFromFingerprint.get() ?: return
        val t = transform.get()
        Matrix.setIdentityM(localModel4, 0)
        Matrix.translateM(localModel4, 0, t.panX, t.panY, 0f)
        Matrix.rotateM(localModel4, 0, t.rotationZDeg, 0f, 0f, 1f)
        Matrix.scaleM(localModel4, 0, t.scale, t.scale, 1f)
        Matrix.multiplyMM(model4, 0, anchor, 0, localModel4, 0)
        val view = attitude.viewForDraw(nowNs, frame.viewMatrix, predictedView)
        overlayRenderer.draw(
            view,
            projection,
            model4,
            contentRotation = buildContentRotation(t.rotationXDeg, t.rotationYDeg),
        )
    }

    private fun displayProjectionFor(frameIntrinsics: CameraIntrinsics): FloatArray? {
        val width = surfaceWidth
        val height = surfaceHeight
        if (
            frameIntrinsics != displayProjectionIntrinsics ||
            width != displayProjectionWidth ||
            height != displayProjectionHeight
        ) {
            displayProjectionIntrinsics = frameIntrinsics
            displayProjectionWidth = width
            displayProjectionHeight = height
            displayProjection = CameraFillCenter.projection(frameIntrinsics, width, height)
        }
        return displayProjection
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
        coverageGlow?.releaseGlResources()
    }
}
