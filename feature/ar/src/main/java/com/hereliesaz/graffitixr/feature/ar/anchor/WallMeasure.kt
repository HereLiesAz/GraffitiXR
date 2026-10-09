package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Two-tap wall measurement (BACKLOG Phase 6, step 1 "Measure"). Pure geometry, no Android/ARCore.
 *
 * A screen tap becomes a camera ray; the ray is intersected with the plane the design is drawn on
 * (the overlay base frame's local z = 0, [intersectWallWorld]). Using that plane rather than ARCore
 * plane hit-tests means both points lie on the plane the artwork lives on (what a later grid needs)
 * and wall ends outside ARCore's detected plane polygon — the usual case — still measure.
 *
 * The caller then stores each hit relative to a frame that does NOT move with fusion corrections
 * ([toFrameLocal] against the unfused ARCore consensus anchor). The drawn frame does move: a
 * correction of δ between the two taps would otherwise add up to |δ| (or L·δθ for a rotation at
 * distance L) to the width. Only the plane's orientation is taken from the drawn frame.
 *
 * Matrices are column-major OpenGL. `view` is the render camera's view matrix (rigid); `proj` is a
 * standard perspective projection (`proj[11] == -1`, `proj[15] == 0`), which is what ARCore's
 * `Camera.getProjectionMatrix` returns.
 */
object WallMeasure {
    /** Accepted camera-to-hit range (m), the same sanity bounds as the renderer's hit tests. */
    const val MIN_RANGE_M = 0.1f
    const val MAX_RANGE_M = 10f

    /** Accepted measured width (m); matches the standalone reference-width bounds. */
    const val MIN_WIDTH_M = 0.05f
    const val MAX_WIDTH_M = 100f

    /**
     * Rays closer than this to parallel with the wall (degrees between ray and wall normal) are
     * refused: the hit position becomes hypersensitive to normal error at grazing angles.
     */
    const val MAX_OBLIQUITY_DEG = 75f

    /** Maximum deviation of the wall frame's scale from 1 before it is treated as non-rigid. */
    private const val RIGID_SCALE_TOLERANCE = 1e-2f

    class Ray(val origin: FloatArray, val direction: FloatArray)

    /**
     * World-space ray through normalised screen point ([nx], [ny]) in [0,1] (y down), or null for a
     * non-perspective projection or non-finite input.
     */
    fun screenRay(nx: Float, ny: Float, view: FloatArray, proj: FloatArray): Ray? {
        if (view.size != 16 || proj.size != 16) return null
        if (!nx.isFinite() || !ny.isFinite()) return null
        if (proj[11] != -1f || proj[0] == 0f || proj[5] == 0f) return null
        val ndcX = 2f * nx - 1f
        val ndcY = 1f - 2f * ny
        // Camera-space point on the z = -1 plane that projects to (ndcX, ndcY). For a perspective
        // matrix, clip.x = P0·x + P8·z and clip.w = -z, so at z = -1: ndcX = P0·x - P8.
        val cx = (ndcX + proj[8]) / proj[0]
        val cy = (ndcY + proj[9]) / proj[5]
        val camToWorld = PoseMath.rigidInverse(view)
        val dx = camToWorld[0] * cx + camToWorld[4] * cy - camToWorld[8]
        val dy = camToWorld[1] * cx + camToWorld[5] * cy - camToWorld[9]
        val dz = camToWorld[2] * cx + camToWorld[6] * cy - camToWorld[10]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (!len.isFinite() || len == 0f) return null
        return Ray(
            origin = floatArrayOf(camToWorld[12], camToWorld[13], camToWorld[14]),
            direction = floatArrayOf(dx / len, dy / len, dz / len),
        )
    }

    /**
     * Intersect [ray] with the local z = 0 plane of [wallModel] and return the hit as wall-local
     * (x, y). Convenience over [intersectWallWorld] + [toFrameLocal].
     */
    fun intersectWallLocal(ray: Ray, wallModel: FloatArray): FloatArray? =
        intersectWallWorld(ray, wallModel)?.let { toFrameLocal(it, wallModel) }?.let { floatArrayOf(it[0], it[1]) }

    /** World point [p] expressed in rigid [frame]'s local coordinates (x, y, z), or null. */
    fun toFrameLocal(p: FloatArray, frame: FloatArray): FloatArray? {
        if (p.size < 3 || frame.size != 16 || frame.any { !it.isFinite() }) return null
        if (abs(PoseMath.scaleOf(frame) - 1f) > RIGID_SCALE_TOLERANCE) return null
        val inv = PoseMath.rigidInverse(frame)
        val out = FloatArray(3) { r -> inv[r] * p[0] + inv[4 + r] * p[1] + inv[8 + r] * p[2] + inv[12 + r] }
        return out.takeIf { o -> o.all { it.isFinite() } }
    }

    /**
     * Intersect [ray] with the local z = 0 plane of [wallModel] and return the WORLD hit (x, y, z),
     * or null when the frame is not rigid, the ray is parallel/grazing, the hit is behind the
     * camera, or it lies outside [MIN_RANGE_M]..[MAX_RANGE_M].
     */
    fun intersectWallWorld(ray: Ray, wallModel: FloatArray): FloatArray? {
        if (wallModel.size != 16 || wallModel.any { !it.isFinite() }) return null
        if (abs(PoseMath.scaleOf(wallModel) - 1f) > RIGID_SCALE_TOLERANCE) return null
        val nx = wallModel[8]; val ny = wallModel[9]; val nz = wallModel[10]
        val d = ray.direction; val o = ray.origin
        val denom = nx * d[0] + ny * d[1] + nz * d[2]
        val cosLimit = kotlin.math.cos(Math.toRadians(MAX_OBLIQUITY_DEG.toDouble())).toFloat()
        if (abs(denom) < cosLimit) return null
        val t = (nx * (wallModel[12] - o[0]) + ny * (wallModel[13] - o[1]) + nz * (wallModel[14] - o[2])) / denom
        if (!t.isFinite() || t < MIN_RANGE_M || t > MAX_RANGE_M) return null
        val hit = floatArrayOf(o[0] + t * d[0], o[1] + t * d[1], o[2] + t * d[2])
        return hit.takeIf { h -> h.all { it.isFinite() } }
    }

    /**
     * Straight-line distance between two points in the same frame (2 or 3 components; a missing z
     * counts as 0), or null outside [MIN_WIDTH_M]..[MAX_WIDTH_M].
     */
    fun widthMeters(a: FloatArray, b: FloatArray): Float? {
        if (a.size < 2 || b.size < 2) return null
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val dz = (if (b.size > 2) b[2] else 0f) - (if (a.size > 2) a[2] else 0f)
        val w = sqrt(dx * dx + dy * dy + dz * dz)
        return w.takeIf { it.isFinite() && it >= MIN_WIDTH_M && it <= MAX_WIDTH_M }
    }
}
