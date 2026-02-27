package com.hereliesaz.graffitixr.feature.ar.computervision

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import org.opencv.android.Utils
import org.opencv.core.Mat
import javax.inject.Inject

/**
 * Handles the computer vision logic for Teleological SLAM (Map Alignment).
 * Matches live camera frames against a stored Fingerprint to recover 3D pose.
 */
class TeleologicalTracker @Inject constructor(private val slamManager: SlamManager) {

    /**
     * Processes a live frame (Bitmap) to find a saved fingerprint.
     * If found, calculates the 3D transform (PnP) and updates the native SLAM engine.
     *
     * @param liveFrame The current camera frame (RGB).
     * @param targetFingerprint The saved feature descriptors we are looking for.
     * @param intrinsics Camera intrinsics [fx, fy, cx, cy].
     */
    fun trackAndCorrect(liveFrame: Bitmap, targetFingerprint: Fingerprint, intrinsics: FloatArray) {
        // Run PnP solver
        val transformMat = ImageProcessingUtils.solvePnP(liveFrame, targetFingerprint, intrinsics)

        if (transformMat != null) {
            // Flatten 4x4 matrix to float array for JNI
            val transformFloats = FloatArray(16)
            var index = 0
            for (row in 0 until 4) {
                for (col in 0 until 4) {
                    val value = transformMat.get(row, col)
                    transformFloats[index++] = value[0].toFloat()
                }
            }

            // Update the Native Engine with the correction matrix
            // Note: In a real SLAM system, we'd fuse this with Kalman filtering.
            // For GraffitiXR Beta, we snap the anchor.
            slamManager.updateAnchorTransform(transformFloats)

            transformMat.release()
        }
    }

    /**
     * Legacy support for processing raw bytes (Y-plane).
     */
    fun processTeleologicalFrame(yData: ByteArray, width: Int, height: Int): Mat {
        // Implementation provided by JNI now for performance,
        // this method remains for Kotlin-side debugging/visualization if needed.
        return Mat()
    }
}