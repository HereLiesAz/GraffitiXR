// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/BridgedHomographyTracker.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.hereliesaz.graffitixr.feature.ar.HomographyArTracker.HomographyPose
import com.hereliesaz.sphereslam.attitude.AttitudeRotationBridge
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.GameRotationAttitudeSource
import com.hereliesaz.sphereslam.math.RotationMath

/**
 * [HomographyArTracker] plus a short gyro bridge: tracks each frame with vision when it can, and for
 * a short window after the last good lock, holds the pose steady by rotating it with the device
 * attitude instead of dropping tracking outright.
 *
 * The attitude and the bridge math are SphereSLAM's: [GameRotationAttitudeSource]
 * (`TYPE_GAME_ROTATION_VECTOR`, the platform's own gyro fusion — no magnetometer, no hand-rolled
 * integrator) and [AttitudeRotationBridge], which rotates the held pose about its camera CENTRE
 * (`[ΔR·R | ΔR·t]`). The bridge never estimates displacement (no accelerometer double integration),
 * so it stays short ([maxBridgeMs]).
 *
 * **Device → camera rotation.** The tracked frame is the raw buffer rotated clockwise by CameraX
 * `rotationDegrees`, so the body→GL-camera rotation is
 * [DeviceCameraRotation.cameraFromDevice]`(sensorOrientation, rotationDegrees)` = `R_z(s − k)` (the
 * display rotation for a rear camera). It is fixed for the life of each reference: the reference
 * attitude and the held pose were both captured under that frame's orientation. (This replaced
 * `GyroOrientationBridge`, which conjugated by `R_z(rotationDegrees)` — the open question its own
 * doc flagged. For a typical 90° rear sensor held in natural portrait that passed 90° where the
 * correct angle is 0°, so an off-axis bridged turn rotated about the wrong axis.)
 *
 * [HomographyFallbackOverlay] wires this to CameraX through [HomographyTrackingAnalyzer].
 */
class BridgedHomographyTracker internal constructor(
    private val tracker: HomographyArTracker,
    private val attitudeSource: GameRotationAttitudeSource?,
    private val latestAttitude: () -> AttitudeSample?,
    /**
     * How long a lost vision lock stays bridged by gyro rotation before this gives up and reports
     * true loss. Kept short deliberately — gyro-only drift is small over a few hundred ms and is not
     * meant to stand in for vision indefinitely. Not yet tuned against real device behaviour.
     */
    private val maxBridgeMs: Long = DEFAULT_MAX_BRIDGE_MS,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(
        context: Context,
        tracker: HomographyArTracker = HomographyArTracker(),
        maxBridgeMs: Long = DEFAULT_MAX_BRIDGE_MS,
    ) : this(tracker, GameRotationAttitudeSource(context), maxBridgeMs = maxBridgeMs)

    private constructor(
        tracker: HomographyArTracker,
        source: GameRotationAttitudeSource,
        maxBridgeMs: Long,
    ) : this(tracker, source, source::latestSample, maxBridgeMs)

    private var lastGoodPose: HomographyPose? = null
    private var bridge: AttitudeRotationBridge? = null
    private var referenceAtMs: Long = -1L

    /** Starts the attitude sensor. Call from the same lifecycle scope that will call [trackFrame]. */
    fun start() {
        attitudeSource?.start()
    }

    /** Stops the attitude sensor. Call when the fallback tracking session ends. */
    fun stop() {
        attitudeSource?.stop()
    }

    /** Delegates to [HomographyArTracker.setReference], and drops any prior bridge state. */
    fun setReference(referenceBitmap: Bitmap, objectHalfW: Float, objectHalfH: Float): Boolean {
        clearBridge()
        return tracker.setReference(referenceBitmap, objectHalfW, objectHalfH)
    }

    fun hasReference(): Boolean = tracker.hasReference()

    fun reset() {
        clearBridge()
        tracker.reset()
    }

    /**
     * Track one live camera frame, bridging through a brief vision failure with gyro rotation.
     *
     * @param rotationDeg CameraX `rotationDegrees` for THIS frame — the clockwise rotation applied to
     *   the buffer to produce [frameBitmap].
     * @param sensorOrientationDeg the camera's `SENSOR_ORIENTATION`.
     * @return a fresh vision-tracked pose, a gyro-bridged hold of the last one, or null once both
     *   vision has failed AND the bridge window has elapsed (or no attitude is available at all).
     */
    fun trackFrame(
        frameBitmap: Bitmap,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        rotationDeg: Int,
        sensorOrientationDeg: Int,
    ): HomographyPose? {
        // Snapshot the reference candidate BEFORE the synchronous vision solve, so its time sits at
        // this frame's capture and any rotation during track() is not dropped from the first bridged
        // pose after a fresh lock. Only committed if this frame's tracking actually succeeds.
        val pendingSample = latestAttitude()
        val pendingAtMs = nowMs()
        val visionPose = tracker.track(frameBitmap, fx, fy, cx, cy)
        if (visionPose != null) {
            lastGoodPose = visionPose
            if (pendingSample != null) {
                bridge = AttitudeRotationBridge(
                    DeviceCameraRotation.cameraFromDevice(sensorOrientationDeg, rotationDeg),
                ).also { it.markReference(pendingSample) }
                referenceAtMs = pendingAtMs
            }
            return visionPose
        }

        val held = lastGoodPose ?: return null
        val activeBridge = bridge
        val elapsed = if (activeBridge == null || referenceAtMs < 0L) -1L else nowMs() - referenceAtMs
        if (elapsed < 0 || elapsed > maxBridgeMs) {
            lastGoodPose = null
            return null
        }

        val deltaCamera = activeBridge?.cameraRotationDelta(latestAttitude()?.quaternion)
            ?: return null // no sensor sample yet — nothing to bridge with.

        // Confidence decays linearly across the bridge window so a caller/UI can visibly distinguish
        // "just locked" from "about to lose it entirely", the same role ARCore's TrackingState plays.
        val decayedConfidence = held.confidence * (1f - elapsed.toFloat() / maxBridgeMs.toFloat())
        return HomographyPose(
            viewMatrix = RotationMath.rotateAboutCameraCentre(held.viewMatrix, deltaCamera),
            confidence = decayedConfidence.coerceAtLeast(0f),
        )
    }

    private fun clearBridge() {
        lastGoodPose = null
        bridge = null
        referenceAtMs = -1L
    }

    companion object {
        const val DEFAULT_MAX_BRIDGE_MS = 400L
    }
}
