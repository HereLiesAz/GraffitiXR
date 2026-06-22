package com.hereliesaz.graffitixr.feature.ar.anchor

/**
 * Assembles metric 3D marks from a SINGLE camera view by back-projecting detected feature pixels
 * onto a known wall plane — the depth-off, single-capture path. No triangulation and no second view:
 * by the time ARCore renders a wall plane **green** ([rendering.PlaneRenderer.PlaneMatchResult.MATCH])
 * it has already fitted that plane and solved its metric pose and distance, so the depth is already
 * paid for. The single capture only supplies the appearance (descriptors); each feature's 3D position
 * is the intersection of its camera ray with that plane.
 *
 * Output points are in the **capture camera's frame, CV convention** (camera looks +Z, depth
 * positive) — exactly the frame [MetricMarks] produces and `MobileGS::generateFingerprint` stores
 * depth-built points in, so the result drops straight into the existing reloc PnP.
 *
 * Pure math — no Android/OpenCV — so it's unit-testable; the OpenCV keypoint detection that produces
 * the pixel list and the native ingest of the result live in [MetricFingerprintBuilder].
 *
 * Conventions: the view matrix is a column-major 4x4 **CV-convention** world→camera transform (camera
 * looks +Z); ARCore/OpenGL views look −Z, so convert them with [MetricMarks.glViewToCv] first. The
 * plane point and normal are in ARCore **world** space (e.g. `plane.centerPose` translation and its
 * local +Y axis); they are moved into the camera frame here.
 */
object PlaneMarks {

    /** A detected feature pixel (u,v) in the captured image. */
    data class Pixel(val u: Float, val v: Float)

    /** kept[i] indexes the input pixel list; pointsCam is flat [x,y,z,...] in the camera's CV frame. */
    class Result(val kept: IntArray, val pointsCam: FloatArray) {
        val count: Int get() = kept.size
    }

    /**
     * Back-project each pixel's camera ray onto the wall plane, keeping only rays that actually hit
     * the plane in front of the camera at a sane depth. A pixel is dropped when its ray is parallel
     * to the plane (no intersection) or the hit is behind the camera / outside [minDepthM]..[maxDepthM].
     *
     * @param pixels detected feature pixels in the captured image.
     * @param cvView CV-convention world→camera view of the capture (see [MetricMarks.glViewToCv]).
     * @param planePointWorld a point on the plane in world space (3 floats) — e.g. plane centre.
     * @param planeNormalWorld the plane normal in world space (3 floats) — need not be unit length.
     */
    fun backProject(
        pixels: List<Pixel>,
        cvView: FloatArray,
        planePointWorld: FloatArray,
        planeNormalWorld: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        minDepthM: Float = 0.1f,
        maxDepthM: Float = 10f,
        eps: Float = 1e-6f,
    ): Result {
        // Plane in the capture's camera frame: a point P and normal N.
        val p = transformPoint(cvView, planePointWorld[0], planePointWorld[1], planePointWorld[2])
        val n = transformNormal(cvView, planeNormalWorld[0], planeNormalWorld[1], planeNormalWorld[2])
        val nDotP = n[0] * p[0] + n[1] * p[1] + n[2] * p[2]

        // Primitive arrays (no boxing) sized to the worst case, then trimmed to the kept count.
        val maxCount = pixels.size
        val keptIndices = IntArray(maxCount)
        val ptsCam = FloatArray(maxCount * 3)
        var count = 0
        for (i in 0 until maxCount) {
            val px = pixels[i]
            // Camera ray in the CV frame: camera at origin looking +Z (dz = 1, so X.z == t).
            val dx = (px.u - cx) / fx
            val dy = (px.v - cy) / fy
            val nDotD = n[0] * dx + n[1] * dy + n[2]
            if (kotlin.math.abs(nDotD) < eps) continue       // ray parallel to plane → no hit
            val t = nDotP / nDotD
            if (t < minDepthM || t > maxDepthM) continue     // behind camera or out of trusted range
            keptIndices[count] = i
            val o = count * 3
            ptsCam[o] = t * dx; ptsCam[o + 1] = t * dy; ptsCam[o + 2] = t
            count++
        }
        return Result(keptIndices.copyOf(count), ptsCam.copyOf(count * 3))
    }

    /** Apply a column-major 4x4 to a point (w=1). */
    private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
    )

    /** Apply only the rotation (upper-left 3x3) of a column-major 4x4 to a direction/normal. */
    private fun transformNormal(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z,
        m[1] * x + m[5] * y + m[9] * z,
        m[2] * x + m[6] * y + m[10] * z,
    )
}
