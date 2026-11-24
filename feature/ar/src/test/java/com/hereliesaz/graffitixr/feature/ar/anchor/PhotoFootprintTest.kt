package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** [PhotoFootprint]: a camera frame's corners on the wall and on the capture photo. */
class PhotoFootprintTest {
    private val page = PhotoFootprint.Page(dpi = 100f, centerXmm = 127f, centerYmm = 101.6f, widthPx = 1000, heightPx = 800)
    private val cam = PhotoFootprint.Camera(fx = 800f, fy = 800f, cx = 480f, cy = 360f, frameW = 960, frameH = 720)

    /** The library's forward map (`referencePixelToWall`), reproduced here. */
    private fun forward(u: Float, v: Float): FloatArray {
        val xMm = (u + 0.5f) / page.dpi * 25.4f
        val yMm = ((page.heightPx - 0.5f) - v) / page.dpi * 25.4f
        return floatArrayOf((xMm - page.centerXmm) * 0.001f, (yMm - page.centerYmm) * 0.001f)
    }

    /** Column-major OpenCV camera_from_doodle for a camera at wall point [c] looking straight at the wall. */
    private fun facing(c: FloatArray): FloatArray {
        // R = diag(1, -1, -1); t = -R * c
        val r = doubleArrayOf(1.0, -1.0, -1.0)
        return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            (-r[0] * c[0]).toFloat(), (-r[1] * c[1]).toFloat(), (-r[2] * c[2]).toFloat(), 1f,
        )
    }

    @Test
    fun `the photo inverse undoes the library's forward map`() {
        for ((u, v) in listOf(0f to 0f, 999f to 799f, 123.5f to 456.25f, -0.5f to 799.5f)) {
            val w = forward(u, v)
            val back = PhotoFootprint.wallToPhotoPixel(w[0], w[1], page)
            assertEquals(u, back[0], 1e-2f); assertEquals(v, back[1], 1e-2f)
        }
    }

    @Test
    fun `a camera square to the wall sees a rectangle, centred on its aim`() {
        val d = 0.5f
        val view = facing(floatArrayOf(0.02f, -0.03f, d))
        val c = PhotoFootprint.wallCorners(view, cam)!!
        val halfW = cam.cx / cam.fx * d; val halfH = cam.cy / cam.fy * d
        // top-left corner: left and up from the aim
        assertEquals(0.02f - halfW, c[0], 1e-4f); assertEquals(-0.03f + halfH, c[1], 1e-4f)
        // bottom-right: right and down
        assertEquals(0.02f + (cam.frameW - cam.cx) / cam.fx * d, c[4], 1e-4f)
        assertEquals(-0.03f - (cam.frameH - cam.cy) / cam.fy * d, c[5], 1e-4f)
    }

    @Test
    fun `the footprint on the photo is where the aimed wall point is`() {
        val aim = forward(300f, 250f)
        val f = PhotoFootprint.onPhoto(facing(floatArrayOf(aim[0], aim[1], 0.4f)), cam, page)!!
        val cx = (f[0] + f[2] + f[4] + f[6]) / 4; val cy = (f[1] + f[3] + f[5] + f[7]) / 4
        assertEquals(300f, cx, 0.5f); assertEquals(250f, cy, 0.5f)
        // nearer means a smaller footprint
        val near = PhotoFootprint.onPhoto(facing(floatArrayOf(aim[0], aim[1], 0.2f)), cam, page)!!
        assertTrue(near[2] - near[0] < f[2] - f[0])
    }

    @Test
    fun `corners of a tilted, shifted camera project back to the frame corners`() {
        val a = Math.toRadians(25.0); val b = Math.toRadians(-15.0)
        // R = Rx(a) Ry(b) applied after the facing flip; build camera_from_doodle = [R | t] and check the round trip.
        val flip = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), -sin(a)), doubleArrayOf(0.0, sin(a), cos(a)))
        val ry = arrayOf(doubleArrayOf(cos(b), 0.0, sin(b)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(b), 0.0, cos(b)))
        fun mul(p: Array<DoubleArray>, q: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { p[i][it] * q[it][j] } } }
        val rot = mul(rx, mul(ry, flip))
        val t = doubleArrayOf(0.03, -0.02, 0.45)
        val view = FloatArray(16)
        for (col in 0..2) for (row in 0..2) view[col * 4 + row] = rot[row][col].toFloat()
        view[12] = t[0].toFloat(); view[13] = t[1].toFloat(); view[14] = t[2].toFloat(); view[15] = 1f
        val c = PhotoFootprint.wallCorners(view, cam)!!
        val px = arrayOf(0f to 0f, 960f to 0f, 960f to 720f, 0f to 720f)
        for (i in 0 until 4) {
            val x = c[i * 2].toDouble(); val y = c[i * 2 + 1].toDouble()
            val camP = DoubleArray(3) { row -> rot[row][0] * x + rot[row][1] * y + t[row] } // doodle z = 0
            assertEquals(px[i].first, (cam.fx * camP[0] / camP[2] + cam.cx).toFloat(), 0.05f)
            assertEquals(px[i].second, (cam.fy * camP[1] / camP[2] + cam.cy).toFloat(), 0.05f)
        }
    }

    @Test
    fun `a camera that cannot see the wall, or bad input, gives null`() {
        // Parallel to the wall: looking along +X of the wall frame.
        val parallel = floatArrayOf(0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f)
        assertNull(PhotoFootprint.wallCorners(parallel, cam))
        // Behind the wall: the camera on the far side, looking away.
        val away = facing(floatArrayOf(0f, 0f, -0.5f))
        assertNull(PhotoFootprint.wallCorners(away, cam))
        assertNull(PhotoFootprint.wallCorners(FloatArray(16) { Float.NaN }, cam))
        assertNull(PhotoFootprint.wallCorners(FloatArray(8), cam))
        assertNotNull(PhotoFootprint.wallCorners(facing(floatArrayOf(0f, 0f, 0.5f)), cam))
    }
}
/** Against the real library mapping. */
class PhotoFootprintLibraryTest {
    @Test
    fun `the photo inverse undoes PhotosphereFingerprintFrame referencePixelToWall`() {
        val w = 1000; val h = 800
        val geometry = com.hereliesaz.sphereslam.SphereSlamPoseMath.pageGeometry(w, h, 96f)
        val page = PhotoFootprint.Page(geometry.referenceDpi, geometry.centerXmm, geometry.centerYmm, w, h)
        for ((u, v) in listOf(0f to 0f, 999f to 799f, 250.5f to 611.25f, 500f to 400f)) {
            val p = com.hereliesaz.sphereslam.PhotosphereFingerprintFrame.referencePixelToWall(u, v, w, h, geometry)
            val back = PhotoFootprint.wallToPhotoPixel(p.x, p.y, page)
            assertEquals(u, back[0], 1e-2f); assertEquals(v, back[1], 1e-2f)
        }
    }
}
