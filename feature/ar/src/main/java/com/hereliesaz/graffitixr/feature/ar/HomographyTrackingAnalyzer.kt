// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/HomographyTrackingAnalyzer.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsicsEstimator
import com.hereliesaz.graffitixr.feature.ar.HomographyArTracker.HomographyPose
import com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation
import com.hereliesaz.graffitixr.feature.ar.rendering.ProjectionMatrix
import com.hereliesaz.graffitixr.nativebridge.YuvConverter

/**
 * One frame's tracked pose plus the GL projection matrix it was solved against — see
 * [HomographyTrackingAnalyzer]. [frameAspect] (width/height of the frame the pose was solved in,
 * AFTER rotation to display orientation) lets the renderer letterbox its viewport to match exactly
 * what [com.hereliesaz.graffitixr.feature.ar.CameraPreview]'s `PreviewView` is showing — see
 * [com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer] for why that match
 * matters (drawing into the full GL surface while the preview beneath it is cropped/letterboxed
 * to a different aspect stretches and mis-scales the overlay relative to what's on screen).
 */
data class HomographyTrackedFrame(val pose: HomographyPose, val projMatrix: FloatArray, val frameAspect: Float)

/**
 * The CameraX `ImageAnalysis.Analyzer` that actually drives [BridgedHomographyTracker] from a
 * live camera feed — the piece that turns Phase 1's tracking math into something fed real frames.
 * Attach via `LifecycleCameraController.setImageAnalysisAnalyzer(executor, this)`; `CameraPreview`
 * already enables the `IMAGE_ANALYSIS` use case unconditionally, so attaching costs nothing until
 * a frame actually arrives, and detaching (`clearImageAnalysisAnalyzer`) stops the work outright.
 *
 * Per frame: decode YUV -> RGBA via [YuvConverter] (the same native path `nativeFeedYuvFrame`
 * uses, not a JPEG round-trip), rotate to display orientation exactly the way
 * [takePictureAsBitmap] already does for stills, rotate the frame's camera intrinsics to match via
 * [CaptureRotation] (the SAME established sensor-to-display convention this codebase already uses
 * for exactly this kind of pixel/intrinsics pair — see its class doc), then hand both to
 * [BridgedHomographyTracker.trackFrame].
 *
 * Not itself lifecycle-aware: the caller attaches/detaches this alongside whatever owns the
 * fallback-tracking session (matching [GyroOrientationBridge]'s own start()/stop() shape).
 */
class HomographyTrackingAnalyzer(
    private val context: Context,
    private val cameraId: String,
    private val tracker: BridgedHomographyTracker,
    private val onFrameTracked: (HomographyTrackedFrame?) -> Unit,
) : ImageAnalysis.Analyzer {

    // Reused across frames at a stable resolution — CameraX delivers a fixed ImageAnalysis
    // resolution in practice, so this allocates once and then never again.
    private var rawBitmap: Bitmap? = null
    private var cachedRawWidth = -1
    private var cachedRawHeight = -1
    private var cachedIntrinsics: CameraIntrinsics? = null

    override fun analyze(image: ImageProxy) {
        try {
            val mediaImage = image.image ?: return // non-Camera2-backed ImageProxy; nothing to convert.
            val rawW = image.width
            val rawH = image.height
            val rotationDeg = image.imageInfo.rotationDegrees

            val raw = rawBitmapFor(rawW, rawH)
            YuvConverter.yuvToRgbaBitmap(mediaImage, raw)

            val rotated = if (rotationDeg != 0) {
                val m = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, rawW, rawH, m, true)
            } else {
                raw
            }

            val intrinsics = intrinsicsFor(rawW, rawH) ?: return
            val rotatedIntrinsics = CaptureRotation.rotateIntrinsics(
                intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy,
                rawW.toFloat(), rawH.toFloat(), rotationDeg,
            )

            val pose = tracker.trackFrame(
                rotated,
                rotatedIntrinsics[0], rotatedIntrinsics[1], rotatedIntrinsics[2], rotatedIntrinsics[3],
                rotationDeg,
            )
            if (pose != null) {
                // rotated.width/height, not rawW/rawH: a 90/270 rotation swaps them, and
                // rotatedIntrinsics above already accounts for that same swap (CaptureRotation) —
                // the projection matrix must be built against the SAME frame the pose was solved in.
                val rotatedFrameIntrinsics = CameraIntrinsics(
                    fx = rotatedIntrinsics[0], fy = rotatedIntrinsics[1],
                    cx = rotatedIntrinsics[2], cy = rotatedIntrinsics[3],
                    width = rotated.width, height = rotated.height,
                )
                val frameAspect = rotated.width.toFloat() / rotated.height.toFloat()
                onFrameTracked(
                    HomographyTrackedFrame(pose, ProjectionMatrix.buildFrom(rotatedFrameIntrinsics), frameAspect),
                )
            } else {
                onFrameTracked(null)
            }

            if (rotated !== raw) rotated.recycle()
        } finally {
            image.close()
        }
    }

    private fun rawBitmapFor(width: Int, height: Int): Bitmap {
        val cached = rawBitmap
        if (cached != null && cachedRawWidth == width && cachedRawHeight == height) return cached
        cached?.recycle()
        cachedRawWidth = width
        cachedRawHeight = height
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { rawBitmap = it }
    }

    private fun intrinsicsFor(rawWidth: Int, rawHeight: Int): CameraIntrinsics? {
        val cached = cachedIntrinsics
        if (cached != null && cached.width == rawWidth && cached.height == rawHeight) return cached
        val estimated = CameraIntrinsicsEstimator.estimate(context, cameraId, rawWidth, rawHeight)
        cachedIntrinsics = estimated
        return estimated
    }
}
