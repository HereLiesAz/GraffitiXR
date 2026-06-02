package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Two-view linear (DLT) triangulation of a point seen from two camera poses. Metric because the poses
 * come from ARCore VIO (world→camera view matrices). Pure math — no Android/OpenCV — so it's unit-
 * testable. This is layer 2 of the metric-depth strategy: recover a mark's real-world distance from the
 * artist's natural step-in/step-back baseline, with no second lens and no ML depth.
 *
 * Conventions: view matrices are column-major 4x4 (OpenGL/ARCore). Projection matrices produced here are
 * 3x4 row-major (length 12, P[r*4+c]). Pixel coords are (u,v) in the same image space the intrinsics
 * (fx,fy,cx,cy) describe.
 */
object Triangulation {

    /** Build a 3x4 projection P = K·[R|t] (row-major, length 12) from a column-major world→camera view. */
    fun projectionFromView(view: FloatArray, fx: Float, fy: Float, cx: Float, cy: Float): FloatArray {
        // [R|t] (3x4): rt[r][c] = view[c*4+r] for the rotation/translation rows.
        // K (3x3): [[fx,0,cx],[0,fy,cy],[0,0,1]]
        val p = FloatArray(12)
        for (c in 0 until 4) {
            val r0 = view[c * 4 + 0] // [R|t] row 0, col c
            val r1 = view[c * 4 + 1] // row 1
            val r2 = view[c * 4 + 2] // row 2
            p[0 * 4 + c] = fx * r0 + cx * r2
            p[1 * 4 + c] = fy * r1 + cy * r2
            p[2 * 4 + c] = r2
        }
        return p
    }

    /** Project a world point through a 3x4 projection into pixel (u,v); null if behind the camera. */
    fun project(p: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val pw = p[8] * x + p[9] * y + p[10] * z + p[11]
        if (pw <= 0f) return null // behind camera (cheirality)
        val pu = p[0] * x + p[1] * y + p[2] * z + p[3]
        val pv = p[4] * x + p[5] * y + p[6] * z + p[7]
        return floatArrayOf(pu / pw, pv / pw)
    }

    /**
     * Triangulate the 3D world point seen at (u0,v0) in camera 0 and (u1,v1) in camera 1.
     * @return [x,y,z] in world coords, or null if the system is degenerate (e.g. zero baseline).
     */
    fun triangulate(p0: FloatArray, p1: FloatArray, u0: Float, v0: Float, u1: Float, v1: Float): FloatArray? {
        // Rows of A x = 0 (x = [X,Y,Z,W]):
        //   u*P[2] - P[0],  v*P[2] - P[1]   for each view.
        val a = arrayOf(
            FloatArray(4) { u0 * p0[8 + it] - p0[0 + it] },
            FloatArray(4) { v0 * p0[8 + it] - p0[4 + it] },
            FloatArray(4) { u1 * p1[8 + it] - p1[0 + it] },
            FloatArray(4) { v1 * p1[8 + it] - p1[4 + it] },
        )
        // Inhomogeneous DLT: fix W=1 → solve the 4x3 system M·[X,Y,Z] = b (b = -A[:,3]) by normal
        // equations (3x3). Robust for finite points (marks on a wall are never at infinity).
        val m = Array(4) { row -> floatArrayOf(a[row][0], a[row][1], a[row][2]) }
        val b = FloatArray(4) { -a[it][3] }
        // N = MᵀM (3x3), rhs = Mᵀb (3)
        val n = Array(3) { i -> FloatArray(3) { j -> (0 until 4).sumOf { (m[it][i] * m[it][j]).toDouble() }.toFloat() } }
        val rhs = FloatArray(3) { i -> (0 until 4).sumOf { (m[it][i] * b[it]).toDouble() }.toFloat() }
        return solve3x3(n, rhs)
    }

    /** Metric baseline between two column-major view matrices' camera centers. */
    fun baselineMeters(view0: FloatArray, view1: FloatArray): Float {
        val c0 = cameraCenter(view0); val c1 = cameraCenter(view1)
        val dx = c0[0] - c1[0]; val dy = c0[1] - c1[1]; val dz = c0[2] - c1[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Camera center in world = -Rᵀ t, for a column-major world→camera view matrix. */
    private fun cameraCenter(view: FloatArray): FloatArray {
        // R (3x3): r[row][col] = view[col*4+row]; t = (view[12],view[13],view[14])
        val tx = view[12]; val ty = view[13]; val tz = view[14]
        // -Rᵀ t : Rᵀ[i][k] = R[k][i] = view[i*4+k]
        return floatArrayOf(
            -(view[0] * tx + view[1] * ty + view[2] * tz),
            -(view[4] * tx + view[5] * ty + view[6] * tz),
            -(view[8] * tx + view[9] * ty + view[10] * tz),
        )
    }

    /** Solve a 3x3 linear system n·x = rhs via the adjugate/determinant; null if near-singular. */
    private fun solve3x3(n: Array<FloatArray>, rhs: FloatArray): FloatArray? {
        val a = n[0][0]; val b = n[0][1]; val c = n[0][2]
        val d = n[1][0]; val e = n[1][1]; val f = n[1][2]
        val g = n[2][0]; val h = n[2][1]; val i = n[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        // Relative singularity test: N's entries can be ~1e5, so an absolute epsilon misses a
        // rank-deficient (e.g. zero-baseline) system whose det is float-noise, not zero. Scale by
        // the matrix magnitude (det grows as scale^3 for a 3x3).
        val scale = maxOf(abs(a), abs(b), abs(c), abs(d), abs(e), abs(f), abs(g), abs(h), abs(i))
        if (scale < 1e-12f || abs(det) < 1e-4f * scale * scale * scale) return null
        val inv = 1f / det
        val x = inv * (rhs[0] * (e * i - f * h) - b * (rhs[1] * i - f * rhs[2]) + c * (rhs[1] * h - e * rhs[2]))
        val y = inv * (a * (rhs[1] * i - f * rhs[2]) - rhs[0] * (d * i - f * g) + c * (d * rhs[2] - rhs[1] * g))
        val z = inv * (a * (e * rhs[2] - rhs[1] * h) - b * (d * rhs[2] - rhs[1] * g) + rhs[0] * (d * h - e * g))
        return floatArrayOf(x, y, z)
    }
}
