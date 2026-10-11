// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/HomographyTrackingAnalyzer.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.camera.CaptureRotation
import com.hereliesaz.sphereslam.camera.LumaFrameTransform
import com.hereliesaz.sphereslam.camera.ProjectionMatrix
import com.hereliesaz.sphereslam.reloc.PlanarTracker

/**
 * One frame's tracked pose plus the GL projection matrix it was solved against — see
 * [HomographyTrackingAnalyzer]. [frameIntrinsics] are the intrinsics of that same frame AFTER
 * rotation to display orientation; [frameAspect] is its width/height. The renderer re-derives its
 * on-screen projection from [frameIntrinsics] through the `FILL_CENTER` crop
 * [com.hereliesaz.graffitixr.feature.ar.CameraPreview]'s `PreviewView` applies — see
 * [com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer] for why that match
 * matters (drawing the frame's own projection over a preview cropped to a different aspect
 * stretches and mis-scales the overlay relative to what's on screen).
 */
data class HomographyTrackedFrame(
    val pose: PlanarTracker.Pose,
    val projMatrix: FloatArray,
    val frameAspect: Float,
    val frameIntrinsics: CameraIntrinsics,
)

/**
 * The CameraX `ImageAnalysis.Analyzer` that drives [BridgedHomographyTracker] from a live camera
 * feed. Attach via `LifecycleCameraController.setImageAnalysisAnalyzer(executor, this)`;
 * `CameraPreview` already enables the `IMAGE_ANALYSIS` use case unconditionally, so attaching costs
 * nothing until a frame actually arrives, and detaching (`clearImageAnalysisAnalyzer`) stops the
 * work outright.
 *
 * Per frame: pack the full Y plane and rotate it to display orientation with SphereSLAM's
 * [LumaFrameTransform] (the same path the standalone analyzers use), rotate the frame's camera
 * intrinsics to match via [CaptureRotation] (the SAME sensor-to-display convention, so pixels and
 * intrinsics describe one image), then hand both to [BridgedHomographyTracker.trackFrame].
 *
 * Not itself lifecycle-aware: the caller attaches/detaches this alongside whatever owns the
 * fallback-tracking session (matching [BridgedHomographyTracker]'s own start()/stop() shape).
 */
class HomographyTrackingAnalyzer(
    private val context: Context,
    private val cameraId: String,
    private val tracker: BridgedHomographyTracker,
    private val onFrameTracked: (HomographyTrackedFrame?) -> Unit,
) : ImageAnalysis.Analyzer {

    private var cachedIntrinsics: CameraIntrinsics? = null

    // The gyro bridge needs the sensor mounting to map device attitude into the tracked frame.
    private val sensorOrientationDeg: Int = runCatching {
        val raw = context.getSystemService(CameraManager::class.java)
            .getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: DEFAULT_SENSOR_ORIENTATION_DEG
        if (raw % 90 == 0) raw else DEFAULT_SENSOR_ORIENTATION_DEG
    }.getOrDefault(DEFAULT_SENSOR_ORIENTATION_DEG)

    override fun analyze(image: ImageProxy) {
        try {
            val planes = image.planes
            if (planes.isEmpty()) return // no Y plane; nothing to track.
            val y = planes[0]
            val rawW = image.width
            val rawH = image.height
            val rotationDeg = image.imageInfo.rotationDegrees

            val rotated = LumaFrameTransform.packAndRotate(
                source = y.buffer,
                width = rawW,
                height = rawH,
                rowStride = y.rowStride,
                pixelStride = y.pixelStride,
                rotationDegrees = rotationDeg,
            )

            val intrinsics = intrinsicsFor(rawW, rawH) ?: return
            val rotatedIntrinsics = CaptureRotation.rotateIntrinsics(
                intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy,
                rawW.toFloat(), rawH.toFloat(), rotationDeg,
            )
            // rotated.width/height, not rawW/rawH: a 90/270 rotation swaps them, and
            // rotatedIntrinsics above already accounts for that same swap (CaptureRotation) — the
            // projection matrix must be built against the SAME frame the pose was solved in.
            val rotatedFrameIntrinsics = CameraIntrinsics(
                fx = rotatedIntrinsics[0], fy = rotatedIntrinsics[1],
                cx = rotatedIntrinsics[2], cy = rotatedIntrinsics[3],
                width = rotated.width, height = rotated.height,
            )

            val pose = tracker.trackFrame(
                rotated.bytes,
                rotated.width,
                rotated.height,
                rotatedFrameIntrinsics,
                rotationDeg,
                sensorOrientationDeg,
            )
            if (pose != null) {
                val frameAspect = rotated.width.toFloat() / rotated.height.toFloat()
                onFrameTracked(
                    HomographyTrackedFrame(
                        pose,
                        ProjectionMatrix.buildFrom(rotatedFrameIntrinsics),
                        frameAspect,
                        rotatedFrameIntrinsics,
                    ),
                )
            } else {
                onFrameTracked(null)
            }
        } finally {
            image.close()
        }
    }

    private fun intrinsicsFor(rawWidth: Int, rawHeight: Int): CameraIntrinsics? {
        val cached = cachedIntrinsics
        if (cached != null && cached.width == rawWidth && cached.height == rawHeight) return cached
        val estimated = CameraIntrinsicsEstimator.estimate(context, cameraId, rawWidth, rawHeight)
        cachedIntrinsics = estimated
        return estimated
    }

    private companion object {
        /** Typical rear-camera mounting, used only when Camera2 cannot report SENSOR_ORIENTATION. */
        const val DEFAULT_SENSOR_ORIENTATION_DEG = 90
    }
}
