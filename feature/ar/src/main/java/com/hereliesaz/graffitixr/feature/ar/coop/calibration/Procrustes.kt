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

        // Kabsch: for H = Σ s_i ⊗ d_i = M R^T (M sym-pos), polar decomp gives the
        // orthogonal factor as R^T, so transpose to recover R.
        val rotation = kabschRotation(h)?.transpose() ?: return null
        val translation = dstCentroid - (rotation * srcCentroid)
        return Mat4.fromRotationTranslation(rotation, translation)
    }

    /**
     * Polar-decomposition Newton iteration: X_{k+1} = 0.5 (X_k + (X_k^T)^{-1}).
     * Converges quadratically to the orthogonal factor R of H = R P
     * for any non-singular H. Reflection-guarded so det(R) > 0.
     */
    private fun kabschRotation(h: Mat3): Mat3? {
        if (kotlin.math.abs(h.det()) < 1e-9f) return null
        var r = h
        var prevDiff = Float.MAX_VALUE
        repeat(40) {
            val rt = r.transpose()
            val rtInv = rt.inverse() ?: return null
            val next = avg(r, rtInv)
            prevDiff = frobeniusDiff(next, r)
            r = next
            if (prevDiff < 1e-7f) return@repeat
        }
        return if (r.det() < 0f) {
            Mat3(
                r.m00, r.m01, -r.m02,
                r.m10, r.m11, -r.m12,
                r.m20, r.m21, -r.m22,
            )
        } else r
    }

    private fun avg(a: Mat3, b: Mat3): Mat3 = Mat3(
        (a.m00 + b.m00) * 0.5f, (a.m01 + b.m01) * 0.5f, (a.m02 + b.m02) * 0.5f,
        (a.m10 + b.m10) * 0.5f, (a.m11 + b.m11) * 0.5f, (a.m12 + b.m12) * 0.5f,
        (a.m20 + b.m20) * 0.5f, (a.m21 + b.m21) * 0.5f, (a.m22 + b.m22) * 0.5f,
    )

    private fun frobeniusDiff(a: Mat3, b: Mat3): Float {
        val d00 = a.m00 - b.m00; val d01 = a.m01 - b.m01; val d02 = a.m02 - b.m02
        val d10 = a.m10 - b.m10; val d11 = a.m11 - b.m11; val d12 = a.m12 - b.m12
        val d20 = a.m20 - b.m20; val d21 = a.m21 - b.m21; val d22 = a.m22 - b.m22
        return kotlin.math.sqrt(
            d00 * d00 + d01 * d01 + d02 * d02 +
            d10 * d10 + d11 * d11 + d12 * d12 +
            d20 * d20 + d21 * d21 + d22 * d22
        )
    }
}
