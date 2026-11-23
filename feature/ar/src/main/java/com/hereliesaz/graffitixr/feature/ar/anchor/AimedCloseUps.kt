package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs

/**
 * Which deferred close-ups ([CloseUpFeatures.detect]'s `onDeferred`) the camera is about to need: the
 * ones its frame overlaps. Pure geometry on the photo's pixels, so it can be tested without a device.
 */
internal object AimedCloseUps {

    /** A crop counts when at least this share of the camera's footprint lies inside it. */
    const val MIN_OVERLAP = 0.05f

    /**
     * The [deferred] views whose rectangle holds at least [minOverlap] of the camera frame's footprint
     * [footprint] (the frame's four corners on the photo, `[x0, y0, x1, y1, x2, y2, x3, y3]`, a convex
     * quadrilateral in either winding), most overlapped first; equal overlaps keep their order. Empty when
     * the footprint is degenerate or not finite.
     */
    fun select(footprint: FloatArray, deferred: List<CloseUpFeatures.View>, minOverlap: Float = MIN_OVERLAP): List<CloseUpFeatures.View> {
        if (footprint.size != 8 || footprint.any { !it.isFinite() }) return emptyList()
        val poly = List(4) { floatArrayOf(footprint[it * 2], footprint[it * 2 + 1]) }
        val whole = area(poly)
        if (whole <= 1e-6f) return emptyList()
        return deferred
            .map { it to clippedArea(poly, it) / whole }
            .filter { it.second >= minOverlap }
            .sortedByDescending { it.second } // stable: ties keep the caller's order
            .map { it.first }
    }

    private fun area(p: List<FloatArray>): Float {
        if (p.size < 3) return 0f
        var a = 0.0
        for (i in p.indices) {
            val q = p[(i + 1) % p.size]
            a += p[i][0].toDouble() * q[1] - q[0].toDouble() * p[i][1]
        }
        return abs(a / 2.0).toFloat()
    }

    /** The area of [poly] inside [v]'s rectangle (Sutherland-Hodgman against its four edges). */
    private fun clippedArea(poly: List<FloatArray>, v: CloseUpFeatures.View): Float {
        var out = poly
        val x0 = v.x.toFloat(); val y0 = v.y.toFloat(); val x1 = (v.x + v.w).toFloat(); val y1 = (v.y + v.h).toFloat()
        out = clip(out, { it[0] >= x0 }) { a, b -> cross(a, b, 0, x0) }
        out = clip(out, { it[0] <= x1 }) { a, b -> cross(a, b, 0, x1) }
        out = clip(out, { it[1] >= y0 }) { a, b -> cross(a, b, 1, y0) }
        out = clip(out, { it[1] <= y1 }) { a, b -> cross(a, b, 1, y1) }
        return area(out)
    }

    private fun clip(poly: List<FloatArray>, inside: (FloatArray) -> Boolean, cut: (FloatArray, FloatArray) -> FloatArray): List<FloatArray> {
        if (poly.isEmpty()) return poly
        val out = ArrayList<FloatArray>(poly.size + 2)
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val ia = inside(a); val ib = inside(b)
            if (ia) out += a
            if (ia != ib) out += cut(a, b)
        }
        return out
    }

    /** Where segment a-b meets the line `coordinate[axis] == at`. */
    private fun cross(a: FloatArray, b: FloatArray, axis: Int, at: Float): FloatArray {
        val t = (at - a[axis]) / (b[axis] - a[axis])
        return floatArrayOf(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
    }
}
