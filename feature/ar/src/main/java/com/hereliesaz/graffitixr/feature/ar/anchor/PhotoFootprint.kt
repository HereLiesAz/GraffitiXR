package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs

/**
 * Where a camera frame lands on the capture photo, for choosing the close-ups it is about to need
 * ([AimedCloseUps]). Pure geometry for the standalone (page-frame) doodle: the doodle frame is the
 * centred wall frame of `PhotosphereFingerprintFrame` (+X right, +Y up, +Z toward the camera, metres),
 * and a relocalization result is the OpenCV `camera_from_doodle` view (column-major 16; x right, y down,
 * z forward), as `SlamManager.getRelocResultInto` publishes it in `[0..15]`.
 */
internal object PhotoFootprint {
    private const val MILLIMETERS_PER_INCH = 25.4f
    private const val MILLIMETERS_TO_METERS = 0.001f

    /** The page's pixel-to-wall scale and centre, from `SphereSlamPoseMath.PageGeometry`. */
    data class Page(val dpi: Float, val centerXmm: Float, val centerYmm: Float, val widthPx: Int, val heightPx: Int) {
        init { require(dpi > 0f && widthPx > 0 && heightPx > 0) }
    }

    /** The camera's pinhole, in pixels, and the frame it images ([frameW] x [frameH]). */
    data class Camera(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val frameW: Int, val frameH: Int)

    /**
     * Wall-frame pixel inverse: the photo pixel `(u, v)` of wall point `(x, y)` metres, the inverse of
     * `PhotosphereFingerprintFrame.referencePixelToWall` (`x_mm = (u + 0.5)/dpi*25.4`,
     * `y_mm = ((h - 0.5) - v)/dpi*25.4`, then the page centre subtracted).
     */
    fun wallToPhotoPixel(x: Float, y: Float, page: Page): FloatArray {
        val xMm = x / MILLIMETERS_TO_METERS + page.centerXmm
        val yMm = y / MILLIMETERS_TO_METERS + page.centerYmm
        val u = xMm / MILLIMETERS_PER_INCH * page.dpi - 0.5f
        val v = (page.heightPx - 0.5f) - yMm / MILLIMETERS_PER_INCH * page.dpi
        return floatArrayOf(u, v)
    }

    /**
     * The frame's four corners (top-left, top-right, bottom-right, bottom-left) on the wall plane `z = 0`,
     * `[x0, y0, …, x3, y3]` metres, or null when any corner's ray does not meet the plane in front of the
     * camera (it looks parallel to the wall, or above its horizon) or an input is not finite.
     */
    fun wallCorners(cameraFromDoodle: FloatArray, camera: Camera): FloatArray? {
        if (cameraFromDoodle.size != 16 || cameraFromDoodle.any { !it.isFinite() }) return null
        if (camera.fx <= 0f || camera.fy <= 0f || !camera.cx.isFinite() || !camera.cy.isFinite()) return null
        val m = cameraFromDoodle // column-major: m[col * 4 + row]
        // Rotation R (camera = R * doodle + t) and the camera centre C = -R^T t, in the doodle frame.
        fun r(row: Int, col: Int) = m[col * 4 + row].toDouble()
        val t = doubleArrayOf(m[12].toDouble(), m[13].toDouble(), m[14].toDouble())
        val c = DoubleArray(3) { j -> -(r(0, j) * t[0] + r(1, j) * t[1] + r(2, j) * t[2]) }
        val corners = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(camera.frameW.toFloat(), 0f),
            floatArrayOf(camera.frameW.toFloat(), camera.frameH.toFloat()), floatArrayOf(0f, camera.frameH.toFloat()),
        )
        val out = FloatArray(8)
        for ((i, px) in corners.withIndex()) {
            val ray = doubleArrayOf((px[0] - camera.cx) / camera.fx.toDouble(), (px[1] - camera.cy) / camera.fy.toDouble(), 1.0)
            val d = DoubleArray(3) { j -> r(0, j) * ray[0] + r(1, j) * ray[1] + r(2, j) * ray[2] } // R^T * ray
            if (abs(d[2]) < 1e-9) return null
            val s = -c[2] / d[2]
            if (!(s > 0.0)) return null
            out[i * 2] = (c[0] + s * d[0]).toFloat()
            out[i * 2 + 1] = (c[1] + s * d[1]).toFloat()
        }
        return if (out.all { it.isFinite() }) out else null
    }

    /** [wallCorners] as photo pixels, the footprint [AimedCloseUps.select] takes; null when the frame misses the wall. */
    fun onPhoto(cameraFromDoodle: FloatArray, camera: Camera, page: Page): FloatArray? {
        val wall = wallCorners(cameraFromDoodle, camera) ?: return null
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val p = wallToPhotoPixel(wall[i * 2], wall[i * 2 + 1], page)
            out[i * 2] = p[0]; out[i * 2 + 1] = p[1]
        }
        return out
    }
}
