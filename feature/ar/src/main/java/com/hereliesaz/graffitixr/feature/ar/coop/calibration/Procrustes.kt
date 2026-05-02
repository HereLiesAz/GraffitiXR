package com.hereliesaz.graffitixr.feature.ar.coop.calibration

import com.hereliesaz.graffitixr.common.sensor.Vec3

internal object Procrustes {

    /**
     * Solve rigid transform T such that T·src ≈ dst, minimizing
     * Σ ||dst_i - T·src_i||². Iterative polar decomposition for the
     * rotation part. Returns null if input is colinear/degenerate.
     */
    fun solve(srcPoints: List<Vec3>, dstPoints: List<Vec3>): Mat4? {
        require(srcPoints.size == dstPoints.size) { "size mismatch" }
        require(srcPoints.size >= 3) { "need at least 3 points" }

        val n = srcPoints.size
        val srcCentroid = srcPoints.fold(Vec3.ZERO) { acc, v -> acc + v } / n.toFloat()
        val dstCentroid = dstPoints.fold(Vec3.ZERO) { acc, v -> acc + v } / n.toFloat()

        var h00 = 0f; var h01 = 0f; var h02 = 0f
        var h10 = 0f; var h11 = 0f; var h12 = 0f
        var h20 = 0f; var h21 = 0f; var h22 = 0f
        for (i in 0 until n) {
            val s = srcPoints[i] - srcCentroid
            val d = dstPoints[i] - dstCentroid
            h00 += s.x * d.x; h01 += s.x * d.y; h02 += s.x * d.z
            h10 += s.y * d.x; h11 += s.y * d.y; h12 += s.y * d.z
            h20 += s.z * d.x; h21 += s.z * d.y; h22 += s.z * d.z
        }
        val h = Mat3(h00, h01, h02, h10, h11, h12, h20, h21, h22)

        val rotation = kabschRotation(h) ?: return null
        val translation = dstCentroid - (rotation * srcCentroid)
        return Mat4.fromRotationTranslation(rotation, translation)
    }

    private fun kabschRotation(h: Mat3): Mat3? {
        var r = Mat3.IDENTITY
        var current = h
        repeat(10) {
            val rNext = current
            val rt = rNext.transpose()
            val rtR = rt * rNext
            val invSqrt = invSqrtSymmetric3x3(rtR) ?: return null
            current = rNext * invSqrt
            r = current
        }
        return if (r.det() < 0f) {
            Mat3(
                r.m00, r.m01, -r.m02,
                r.m10, r.m11, -r.m12,
                r.m20, r.m21, -r.m22,
            )
        } else r
    }

    private fun invSqrtSymmetric3x3(m: Mat3): Mat3? {
        var x = Mat3.IDENTITY
        repeat(20) {
            val xx = x * x
            val mxx = m * xx
            val term = Mat3(
                3f - mxx.m00, -mxx.m01, -mxx.m02,
                -mxx.m10, 3f - mxx.m11, -mxx.m12,
                -mxx.m20, -mxx.m21, 3f - mxx.m22,
            )
            x = scaledMultiply(x, term, 0.5f)
        }
        return x
    }

    private fun scaledMultiply(a: Mat3, b: Mat3, scale: Float): Mat3 {
        val p = a * b
        return Mat3(
            p.m00 * scale, p.m01 * scale, p.m02 * scale,
            p.m10 * scale, p.m11 * scale, p.m12 * scale,
            p.m20 * scale, p.m21 * scale, p.m22 * scale,
        )
    }
}
