package com.hereliesaz.graffitixr.feature.ar.computervision

import android.graphics.Bitmap
import android.opengl.Matrix
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import javax.inject.Inject

class TeleologicalTracker @Inject constructor(
    private val slamManager: SlamManager
) {

    /**
     * Computes the alignment correction between the current AR view and the saved Map.
     *
     * @param bitmap Current camera frame.
     * @param fingerprint The target fingerprint (features + 3D points).
     * @param intrinsics Camera intrinsics [fx, fy, cx, cy].
     * @param currentViewMatrix The current view matrix from ARCore (World -> Camera).
     * @return true if alignment was successful and applied, false otherwise.
     */
    suspend fun computeCorrection(
        bitmap: Bitmap,
        fingerprint: Fingerprint,
        intrinsics: FloatArray,
        currentViewMatrix: FloatArray
    ): Boolean = withContext(Dispatchers.Default) {
        // 1. Solve PnP to get Pose of Map relative to Camera
        val pnpMat = ImageProcessingUtils.solvePnP(bitmap, fingerprint, intrinsics) ?: return@withContext false

        try {
            // 2. Convert OpenCV Mat (Row-Major) to OpenGL FloatArray (Column-Major)
            val pnpFloat = FloatArray(16)
            // OpenCV Mat is Row-Major:
            // [0, 1, 2, 3]
            // [4, 5, 6, 7] ...
            // OpenGL FloatArray is Column-Major:
            // [0, 4, 8, 12, 1, 5, 9, 13, ...]

            // We iterate rows i, cols j of Mat.
            // In column-major array, index = j * 4 + i
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    val value = pnpMat.get(i, j)
                    if (value != null) {
                        pnpFloat[j * 4 + i] = value[0].toFloat()
                    }
                }
            }

            // 3. Compute Alignment Matrix
            // We want M_align such that: V_curr * M_align = T_pnp
            // So M_align = V_curr^-1 * T_pnp

            val invView = FloatArray(16)
            val success = Matrix.invertM(invView, 0, currentViewMatrix, 0)
            if (!success) return@withContext false

            val alignment = FloatArray(16)
            Matrix.multiplyMM(alignment, 0, invView, 0, pnpFloat, 0)

            // 4. Apply to SLAM Manager
            slamManager.alignMap(alignment)
            return@withContext true

        } finally {
            pnpMat.release()
        }
    }
}
