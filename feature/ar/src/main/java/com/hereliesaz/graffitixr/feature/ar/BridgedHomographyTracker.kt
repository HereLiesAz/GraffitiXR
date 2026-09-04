// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/BridgedHomographyTracker.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.feature.ar.HomographyArTracker.HomographyPose
import com.hereliesaz.graffitixr.feature.ar.util.RotationDeltaMath

/**
 * [HomographyArTracker] plus [GyroOrientationBridge]: tracks each frame with vision when it can,
 * and for a short window after the last good lock, holds the pose steady by rotating it with the
 * gyroscope instead of dropping tracking outright — see [GyroOrientationBridge]'s doc for what
 * that bridge does and does not cover (no accelerometer, no estimated displacement — only ever the
 * algebraically correct carry of a fixed camera centre through a known rotation; its Z-axis
 * alignment is platform-guaranteed, its `rotationDeg` quarter-turn sign is asserted but not yet
 * device-verified).
 *
 * This is still upstream of Phase 2's CameraX/`OverlayRenderer`/`EditorMode` wiring — it composes
 * the two Phase-1 pieces into the one call a live-tracking loop will actually want
 * (`trackFrame`), but nothing here touches the camera pipeline or the renderer yet.
 */
class BridgedHomographyTracker(
    context: Context,
    private val tracker: HomographyArTracker = HomographyArTracker(),
    private val bridge: GyroOrientationBridge = GyroOrientationBridge(context),
    /**
     * How long a lost vision lock stays bridged by gyro rotation before this gives up and reports
     * true loss. Kept short deliberately — see [GyroOrientationBridge]'s doc on why gyro-only
     * drift is small over a few hundred ms and not meant to stand in for vision indefinitely.
     * Not yet tuned against real device/tracking-loss behavior; Phase 2's on-device pass should
     * treat this as a starting point, not a settled constant.
     */
    private val maxBridgeMs: Long = 400L,
) {
    private var lastGoodPose: HomographyPose? = null

    /** Starts the gyro sensor. Call from the same lifecycle scope that will call [trackFrame]. */
    fun start() = bridge.start()

    /** Stops the gyro sensor. Call when the fallback tracking session ends. */
    fun stop() = bridge.stop()

    /** Delegates to [HomographyArTracker.setReference], and drops any prior bridge state. */
    fun setReference(referenceBitmap: Bitmap, objectHalfW: Float, objectHalfH: Float): Boolean {
        lastGoodPose = null
        bridge.clearReference()
        return tracker.setReference(referenceBitmap, objectHalfW, objectHalfH)
    }

    fun hasReference(): Boolean = tracker.hasReference()

    fun reset() {
        lastGoodPose = null
        bridge.clearReference()
        tracker.reset()
    }

    /**
     * Track one live camera frame, bridging through a brief vision failure with gyro rotation.
     *
     * @param rotationDeg the sensor-to-display quarter-turn — see [GyroOrientationBridge.cameraRotationDelta].
     * @return a fresh vision-tracked pose, a gyro-bridged hold of the last one, or null once both
     *   vision has failed AND the bridge window has elapsed (or gyro isn't available at all).
     */
    fun trackFrame(frameBitmap: Bitmap, fx: Float, fy: Float, cx: Float, cy: Float, rotationDeg: Int): HomographyPose? {
        val visionPose = tracker.track(frameBitmap, fx, fy, cx, cy)
        if (visionPose != null) {
            lastGoodPose = visionPose
            bridge.markReference()
            return visionPose
        }

        val held = lastGoodPose ?: return null
        val elapsed = bridge.msSinceReference()
        if (elapsed < 0 || elapsed > maxBridgeMs) {
            lastGoodPose = null
            return null
        }

        // Apply the SAME rotation delta to both the rotation block and the translation column —
        // holding translation fixed while only rotating moves the camera centre even when the
        // phone hasn't actually moved (see GyroOrientationBridge's class doc). `t' = ΔR · t` is
        // the correct way to carry the (unmoved) camera centre through a known rotation; it is
        // still not a translation ESTIMATE — no accelerometer, no new displacement assumed.
        val deltaCamera = bridge.cameraRotationDelta(rotationDeg)
            ?: return null // no sensor sample yet — nothing to bridge with.
        val heldRotation = rowMajorRotationOf(held.viewMatrix)
        val heldTranslation = translationOf(held.viewMatrix)
        val bridgedRotation = RotationDeltaMath.multiplyMat3(deltaCamera, heldRotation)
        val bridgedTranslation = RotationDeltaMath.multiplyMat3Vec3(deltaCamera, heldTranslation)

        // Confidence decays linearly across the bridge window so a caller/UI can visibly distinguish
        // "just locked" from "about to lose it entirely", the same role ARCore's TrackingState plays.
        val decayedConfidence = held.confidence * (1f - elapsed.toFloat() / maxBridgeMs.toFloat())
        return HomographyPose(
            viewMatrix = viewMatrixOf(bridgedRotation, bridgedTranslation),
            confidence = decayedConfidence.coerceAtLeast(0f),
        )
    }

    private companion object {
        /** Column-major 4x4 (GL convention) -> row-major 3x3 rotation block. */
        fun rowMajorRotationOf(viewMatrix: FloatArray): FloatArray {
            val out = FloatArray(9)
            for (row in 0..2) for (col in 0..2) out[row * 3 + col] = viewMatrix[col * 4 + row]
            return out
        }

        fun translationOf(viewMatrix: FloatArray): FloatArray =
            floatArrayOf(viewMatrix[12], viewMatrix[13], viewMatrix[14])

        /** Row-major 3x3 rotation + translation -> a fresh column-major 4x4 (GL convention). */
        fun viewMatrixOf(rowMajorRotation: FloatArray, translation: FloatArray): FloatArray {
            val m = FloatArray(16)
            for (row in 0..2) for (col in 0..2) m[col * 4 + row] = rowMajorRotation[row * 3 + col]
            m[12] = translation[0]; m[13] = translation[1]; m[14] = translation[2]
            m[15] = 1f
            return m
        }
    }
}
