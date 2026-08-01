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
     * [glViewToCv], then rotated into the **display** camera frame — `IMPLEMENTATION.md` Phase 0,
     * convention B: every capture-side quantity lives in display orientation.
     *
     * The capture path rotates the bitmap and the intrinsics by `rotationNeeded` and left the view
     * matrix in ARCore's physical-camera frame (`Camera.getPose()`, documented as "relative to the
     * image readout"). `PlaneMarks.backProject` then intersected display-frame rays with a
     * sensor-frame plane — the defect in `PAPER.md` §8. This closes it on the view-matrix side.
     *
     * **Direction.** For `rotationDeg = 90` the capture path maps pixel `(u,v)` to `(rawH - v, u)`
     * and swaps `fx`/`fy`, so a sensor-frame ray `d_s` becomes
     * `d_d = ((cy_s - v)/fy_s, (u - cx_s)/fx_s, 1) = (-d_s.y, d_s.x, 1)`, which is `R_z(+90) d_s`
     * with the standard `[[c,-s],[s,c]]`. Hence **display = `R_z(+rotationDeg)` · sensor**, and
     * since `p_display = R_z (V_sensor p_world)`, the rotation **pre-multiplies** the view.
     *
     * `glViewToCv` is deliberately left untouched: the two-keyframe triangulation path supplies its
     * own pixels and intrinsics in a consistent frame already, and has no display rotation to
     * reconcile.
     *
     * @param rotationDeg `(sensorOrientation - displayRotation*90 + 360) % 360`. Must be a multiple
     *   of 90; anything else is a caller bug and throws rather than silently skewing the map.
     */
    fun glViewToCvDisplay(view: FloatArray, rotationDeg: Int): FloatArray {
        val r = ((rotationDeg % 360) + 360) % 360
        require(r % 90 == 0) { "rotationDeg must be a multiple of 90, was $rotationDeg" }
        val cv = glViewToCv(view)
        if (r == 0) return cv                     // R_z(0) == I; skip the multiply entirely
        return rotateZ(cv, r)
    }

    /**
     * The same display rotation as [glViewToCvDisplay], but returning a **GL-convention** view —
     * for the capture view matrix handed to native, which expects GL (`IMPLEMENTATION.md` 0.10).
     *
     * **The sign flips, and that is not a typo.** Writing `D = diag(1,-1,-1)` for the GL↔CV axis
     * conversion that [glViewToCv] performs, we need `GL_display` such that
     * `D · GL_display = R_z(θ) · D · GL_sensor`. Since `D` is its own inverse,
     * `GL_display = D · R_z(θ) · D · GL_sensor`, and
     *
     * ```
     * D · R_z(θ) · D = [[c, s, 0], [-s, c, 0], [0, 0, 1]] = R_z(−θ)
     * ```
     *
     * because negating the Y and Z rows and then the Y and Z columns leaves the diagonal alone and
     * flips the sign of the `xy` off-diagonal pair. So a physical rotation that is `+θ` about the
     * optical axis in CV is `−θ` in GL. `MetricMarksDisplayViewTest` asserts the two converters
     * agree through `glViewToCv` rather than trusting this comment.
     *
     * Why it matters: native pairs this matrix with the fingerprint's 3D points in
     * `computeRectifyHomography` (`viewFp`, `mWallKeypoints3D`) and computes
     * `T = viewCur · inverse(viewFp)`. `viewCur` is `Camera.getViewMatrix`, which is
     * display-oriented, and the points are display-oriented after Phase 0 — so a sensor-frame
     * `viewFp` is the odd one out and the rectifying homography comes out rotated by `R_z`.
     */
    fun glViewDisplayOriented(view: FloatArray, rotationDeg: Int): FloatArray {
        val r = ((rotationDeg % 360) + 360) % 360
        require(r % 90 == 0) { "rotationDeg must be a multiple of 90, was $rotationDeg" }
        if (r == 0) return view.copyOf()
        return rotateZ(view, (360 - r) % 360)      // R_z(−θ)
    }

    /**
     * Pre-multiply a column-major CV view by `R_z(deg)`, rotating the camera-frame *result*.
     *
     * Only rows 0 and 1 change, and only by the exact integer sine/cosine values — written as
     * literals rather than via `Math.cos` so that 90/180/270 are exact and no 6.1e-17 leaks into a
     * matrix that is compared for exactness in tests.
     */
    private fun rotateZ(cv: FloatArray, deg: Int): FloatArray {
        val (c, s) = when (deg) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        val out = cv.copyOf()
        for (col in 0 until 4) {
            val x = cv[col * 4 + 0]
            val y = cv[col * 4 + 1]
            out[col * 4 + 0] = c * x - s * y      // row 0
            out[col * 4 + 1] = s * x + c * y      // row 1
        }
        return out
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
