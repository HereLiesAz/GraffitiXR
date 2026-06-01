package com.hereliesaz.graffitixr.feature.ar.eval

import kotlin.math.acos
import kotlin.math.sqrt

/** The four anti-drift mechanisms under evaluation. Mark-PnP is the reference of truth, not here. */
enum class MechanismId { ARCORE_VIO, VOXEL_RELOC, SURFACE_MESH, CLOUD_OFFSET }

/** Difference between a candidate pose and the mark-PnP truth pose. */
data class PoseError(val translationMm: Float, val rotationDeg: Float)

/** Pure metric math — no Android framework state, fully unit-testable. */
object EvalMetrics {

    /** Both args are column-major 4x4 matrices (OpenGL/ARCore layout). */
    fun poseError(candidate: FloatArray, truth: FloatArray): PoseError {
        val dx = candidate[12] - truth[12]
        val dy = candidate[13] - truth[13]
        val dz = candidate[14] - truth[14]
        val translationMm = sqrt(dx * dx + dy * dy + dz * dz) * 1000f

        // Relative rotation angle from the trace of R = Rc * Rt^T (rotation part only).
        val rc = rotationOnly(candidate)
        val rt = rotationOnly(truth)
        // trace(Rc * Rt^T)
        var trace = 0f
        for (i in 0 until 3) {
            for (k in 0 until 3) {
                trace += rc[i * 3 + k] * rt[i * 3 + k] // since Rt^T row = Rt col
            }
        }
        val cosTheta = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
        val rotationDeg = Math.toDegrees(acos(cosTheta).toDouble()).toFloat()
        return PoseError(translationMm, if (rotationDeg.isNaN()) 0f else rotationDeg)
    }

    // Extract the upper-left 3x3 rotation, row-major, normalizing out scale.
    private fun rotationOnly(m: FloatArray): FloatArray {
        fun colLen(c: Int) = sqrt(m[c*4]*m[c*4] + m[c*4+1]*m[c*4+1] + m[c*4+2]*m[c*4+2]).let { if (it == 0f) 1f else it }
        val s0 = colLen(0); val s1 = colLen(1); val s2 = colLen(2)
        return floatArrayOf(
            m[0]/s0, m[4]/s1, m[8]/s2,
            m[1]/s0, m[5]/s1, m[9]/s2,
            m[2]/s0, m[6]/s1, m[10]/s2,
        )
    }
}
