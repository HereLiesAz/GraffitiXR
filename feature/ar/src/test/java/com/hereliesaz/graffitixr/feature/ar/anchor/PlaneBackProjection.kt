package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.sphereslam.reloc.MetricMarks

/**
 * Test adapter over SphereSLAM's [MetricMarks.backProjectToPlane] (which replaced the app's own
 * `PlaneMarks.backProject`), in the shape the capture-path tests were written against: a list of
 * pixels in, kept indices plus flat capture-camera CV points out.
 */
internal object PlaneBackProjection {

    data class Pixel(val u: Float, val v: Float)

    class Result(val kept: IntArray, val pointsCam: FloatArray) {
        val count: Int get() = kept.size
    }

    fun backProject(
        pixels: List<Pixel>,
        cvView: FloatArray,
        planePointWorld: FloatArray,
        planeNormalWorld: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        minDepthM: Float = 0.1f,
        maxDepthM: Float = 10f,
    ): Result {
        val flat = FloatArray(pixels.size * 2)
        for ((i, p) in pixels.withIndex()) {
            flat[2 * i] = p.u
            flat[2 * i + 1] = p.v
        }
        val r = MetricMarks.backProjectToPlane(
            flat, cvView, planePointWorld, planeNormalWorld, fx, fy, cx, cy, minDepthM, maxDepthM,
        )
        return Result(r.kept, r.points)
    }
}
