package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.hypot

/**
 * Assembles metric 3D marks from two camera views via [Triangulation], for building a wall
 * fingerprint when no depth source is available (the artist's natural step-in/step-back supplies the
 * baseline). Pure math — no Android/OpenCV — so it's unit-testable; the OpenCV keypoint
 * detection/matching that produces the [Corr] list and the native ingest of the result live outside.
 *
 * Output points are in **keyframe-0's camera frame, CV convention** (camera looks +Z, depth
 * positive) — the same frame `MobileGS::generateFingerprint` stores depth-built points in, so the
 * result drops straight into the existing reloc PnP (objPts = these points).
 *
 * Conventions: view matrices are column-major 4x4. [Triangulation] expects **CV-convention** views
 * (camera looks +Z); ARCore/OpenGL views look −Z, so convert them with [glViewToCv] first.
 */
object MetricMarks {

    /** A matched mark: pixel (u,v) in view 0 and view 1, same intrinsics. */
    data class Corr(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    /** kept[i] indexes the input list; pointsCam0 is flat [x,y,z,...] in keyframe-0's CV camera frame. */
    data class Result(val kept: IntArray, val pointsCam0: FloatArray) {
        val count: Int get() = kept.size
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * Convert an OpenGL/ARCore world→camera view (camera looks −Z) to CV convention (camera looks
     * +Z) by negating the camera Y and Z axes — i.e. rows 1 and 2 of the matrix.
     */
    fun glViewToCv(view: FloatArray): FloatArray {
        val v = view.copyOf()
        for (col in 0 until 4) {              // element (row,col) = v[col*4+row], column-major
            v[col * 4 + 1] = -v[col * 4 + 1]  // row 1 (camera Y)
            v[col * 4 + 2] = -v[col * 4 + 2]  // row 2 (camera Z)
        }
        return v
    }

    /**
     * Triangulate matched marks into keyframe-0's CV camera frame, keeping only those that are
     * non-degenerate, in front of both cameras, and reproject within [maxReprojPx] in both views.
     * Returns an empty result if the baseline is below [minBaselineM] (geometry too weak to trust).
     *
     * @param cvView0 / cvView1 CV-convention world→camera views (see [glViewToCv]).
     */
    fun triangulate(
        corrs: List<Corr>,
        cvView0: FloatArray, cvView1: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        maxReprojPx: Float = 2f,
        minBaselineM: Float = 0.03f,
    ): Result {
        if (Triangulation.baselineMeters(cvView0, cvView1) < minBaselineM) {
            return Result(IntArray(0), FloatArray(0))
        }
        val p0 = Triangulation.projectionFromView(cvView0, fx, fy, cx, cy)
        val p1 = Triangulation.projectionFromView(cvView1, fx, fy, cx, cy)
        val kept = ArrayList<Int>(corrs.size)
        val pts = ArrayList<Float>(corrs.size * 3)
        for ((i, c) in corrs.withIndex()) {
            val w = Triangulation.triangulate(p0, p1, c.u0, c.v0, c.u1, c.v1) ?: continue
            val r0 = Triangulation.project(p0, w[0], w[1], w[2]) ?: continue   // cheirality view 0
            val r1 = Triangulation.project(p1, w[0], w[1], w[2]) ?: continue   // cheirality view 1
            if (hypot((r0[0] - c.u0).toDouble(), (r0[1] - c.v0).toDouble()) > maxReprojPx) continue
            if (hypot((r1[0] - c.u1).toDouble(), (r1[1] - c.v1).toDouble()) > maxReprojPx) continue
            val cam0 = transformPoint(cvView0, w[0], w[1], w[2])
            if (cam0[2] <= 0f) continue
            kept.add(i)
            pts.add(cam0[0]); pts.add(cam0[1]); pts.add(cam0[2])
        }
        return Result(kept.toIntArray(), pts.toFloatArray())
    }

    /** Apply a column-major 4x4 to a point (w=1). */
    private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )
}
