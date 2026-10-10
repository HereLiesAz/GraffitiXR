// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/CameraFillCenter.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsTransforms
import com.hereliesaz.sphereslam.camera.ProjectionMatrix
import com.hereliesaz.sphereslam.camera.ScreenIntrinsics
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.tan

/**
 * `PreviewView.ScaleType.FILL_CENTER` as plain math, for everything drawn or tapped over
 * [com.hereliesaz.graffitixr.feature.ar.CameraPreview].
 *
 * The preview fills the screen: the display-upright camera frame is scaled uniformly by the LARGER
 * of the two view/frame ratios and centred, so the overflow on one axis is cropped off equally on
 * both sides. Tracking never sees that crop — the analyzers solve against the full ImageAnalysis
 * frame — so each consumer that meets the screen maps through this one object:
 * - the artwork GL surface's projection ([projection]: frame intrinsics rescaled to cover the view,
 *   then cropped to it, through SphereSLAM's [CameraIntrinsicsTransforms] and
 *   [ProjectionMatrix.buildFrom]) on a full-surface viewport;
 * - screen taps → frame pixels ([viewToFrameNormalized]);
 * - the coverage glow's field of view ([fieldOfViewDegrees]);
 * - Overlay ▸ Gyro's screen intrinsics ([screenIntrinsics], the fill counterpart of
 *   [ScreenIntrinsics.fitCenter]).
 *
 * Library candidate: SphereSLAM's `camera` package ships only the `FIT_CENTER` half
 * ([ScreenIntrinsics]); everything here is pure and belongs beside it.
 *
 * Every function returns null (or the caller's fallback) for sizes that are not set up yet, rather
 * than a plausible-looking guess.
 */
internal object CameraFillCenter {

    /**
     * View pixels per frame pixel: the larger of the two axis ratios, so the frame covers the view.
     * Null when either size is non-positive.
     */
    fun scale(frameWidth: Int, frameHeight: Int, viewWidth: Int, viewHeight: Int): Float? {
        if (frameWidth <= 0 || frameHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        return maxOf(viewWidth.toFloat() / frameWidth, viewHeight.toFloat() / frameHeight)
    }

    /**
     * [frame]'s intrinsics re-expressed for a [viewWidth] × [viewHeight] view showing it
     * `FILL_CENTER`: the visible region, measured in view pixels.
     *
     * Rescale first, crop second. Both library transforms take whole pixels, so the work is rounded
     * at view resolution, where it stays under half a view pixel anywhere on screen (see
     * [coverSize]); cropping the frame first would round in frame pixels instead, each of which
     * spans several view pixels on a phone (a 640×480 analysis frame on a 2400-px-tall screen is
     * ~3.75 view px per frame px).
     */
    fun intrinsics(frame: CameraIntrinsics, viewWidth: Int, viewHeight: Int): CameraIntrinsics? {
        if (!frame.isValid) return null
        val s = scale(frame.width, frame.height, viewWidth, viewHeight) ?: return null
        val coverWidth = coverSize(frame.width * s, viewWidth)
        val coverHeight = coverSize(frame.height * s, viewHeight)
        val covered = CameraIntrinsicsTransforms.rescale(frame, coverWidth, coverHeight)
        return CameraIntrinsicsTransforms.crop(
            intrinsics = covered,
            cropLeft = (coverWidth - viewWidth) / 2,
            cropTop = (coverHeight - viewHeight) / 2,
            cropWidth = viewWidth,
            cropHeight = viewHeight,
        )
    }

    /**
     * Whole-pixel stand-in for the exact covering size [exact] (≥ [view]) on one axis: the nearest
     * size whose overflow past [view] is EVEN, so the centred crop origin is a whole pixel too. The
     * only remaining error is the size's own (< 1 px) spread across the frame, which displaces any
     * on-screen point by under `view / (2·exact)` ≤ ½ px. The binding axis lands on [view] itself
     * (float noise in [exact] included), giving zero error there.
     */
    private fun coverSize(exact: Float, view: Int): Int {
        val base = floor(exact).toInt()
        return (base - 1..base + 2)
            .filter { it >= view && (it - view) % 2 == 0 }
            .minByOrNull { abs(it - exact) }
            ?: view
    }

    /**
     * GL projection for a full-surface viewport over the `FILL_CENTER` preview, with
     * [ProjectionMatrix.buildFrom]'s default clip planes — the same ones the analyzers' frame
     * projections use, so depth behaves identically.
     */
    fun projection(frame: CameraIntrinsics, viewWidth: Int, viewHeight: Int): FloatArray? =
        intrinsics(frame, viewWidth, viewHeight)?.let { ProjectionMatrix.buildFrom(it) }

    /**
     * A point given as fractions of the view ([nx], [ny], top-left origin) → the same point as
     * fractions of the full [frameWidth] × [frameHeight] frame. Exact float math, as `PreviewView`
     * itself transforms. Inside the view always maps inside the frame (fill never shows past an
     * edge). Null when a size is unset or a coordinate is non-finite.
     */
    fun viewToFrameNormalized(
        nx: Float,
        ny: Float,
        frameWidth: Int,
        frameHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray? {
        if (!nx.isFinite() || !ny.isFinite()) return null
        val s = scale(frameWidth, frameHeight, viewWidth, viewHeight) ?: return null
        val offsetX = (viewWidth - frameWidth * s) / 2f
        val offsetY = (viewHeight - frameHeight * s) / 2f
        val u = (nx * viewWidth - offsetX) / s
        val v = (ny * viewHeight - offsetY) / s
        return floatArrayOf(u / frameWidth, v / frameHeight)
    }

    /**
     * `[horizontal, vertical]` field of view in degrees of what the view actually shows — narrower
     * than the frame's own on the cropped axis. For a centred principal point, as
     * [com.hereliesaz.sphereslam.CoverageGlowProjection.projectRegions] assumes.
     */
    fun fieldOfViewDegrees(frame: CameraIntrinsics, viewWidth: Int, viewHeight: Int): FloatArray? {
        val shown = intrinsics(frame, viewWidth, viewHeight) ?: return null
        return floatArrayOf(
            Math.toDegrees(2.0 * atan(shown.width / (2.0 * shown.fx))).toFloat(),
            Math.toDegrees(2.0 * atan(shown.height / (2.0 * shown.fy))).toFloat(),
        )
    }

    /**
     * `[fx, fy, cx, cy]` in view pixels for an [imageWidth] × [imageHeight] frame (focal lengths in
     * the sensor's own orientation) shown `FILL_CENTER` — [ScreenIntrinsics.fitCenter] with the
     * covering scale instead of the fitting one, including its quarter-turn axis swap when the
     * view's orientation differs from the frame's. Principal point at the view centre, as there.
     */
    fun screenIntrinsics(
        imageFx: Float,
        imageFy: Float,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0) { "sizes must be positive" }
        val swap = (imageWidth >= imageHeight) != (viewWidth >= viewHeight)
        val shownW = if (swap) imageHeight else imageWidth
        val shownH = if (swap) imageWidth else imageHeight
        val shownFx = if (swap) imageFy else imageFx
        val shownFy = if (swap) imageFx else imageFy
        val s = maxOf(viewWidth.toFloat() / shownW, viewHeight.toFloat() / shownH)
        return floatArrayOf(shownFx * s, shownFy * s, viewWidth / 2f, viewHeight / 2f)
    }

    /** [screenIntrinsics] for a [CameraIntrinsics]. */
    fun screenIntrinsics(intrinsics: CameraIntrinsics, viewWidth: Int, viewHeight: Int): FloatArray =
        screenIntrinsics(intrinsics.fx, intrinsics.fy, intrinsics.width, intrinsics.height, viewWidth, viewHeight)

    /**
     * [screenIntrinsics] when the camera reports nothing usable — [ScreenIntrinsics.fallback]'s
     * nominal 4:3 frame with [ScreenIntrinsics.FALLBACK_LONG_SIDE_FOV_DEGREES] across its long side.
     */
    fun screenIntrinsicsFallback(viewWidth: Int, viewHeight: Int): FloatArray {
        val w = 4000
        val h = 3000
        val f = (w / 2f) / tan(Math.toRadians(ScreenIntrinsics.FALLBACK_LONG_SIDE_FOV_DEGREES / 2.0)).toFloat()
        return screenIntrinsics(f, f, w, h, viewWidth, viewHeight)
    }
}
