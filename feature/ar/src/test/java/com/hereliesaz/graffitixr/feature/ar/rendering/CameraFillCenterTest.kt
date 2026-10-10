package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.camera.ProjectionMatrix
import com.hereliesaz.sphereslam.camera.ScreenIntrinsics
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan

/**
 * Pure-math correctness for [CameraFillCenter], the `FILL_CENTER` crop every consumer over
 * `CameraPreview` shares. The load-bearing property is registration: a wall point projected through
 * the full tracked frame and then carried onto the screen the way `PreviewView` does must land where
 * the artwork surface's own projection puts it, and a tap must come back to the same frame pixel.
 */
class CameraFillCenterTest {

    // A typical portrait analysis frame (3:4) on a tall 9:20 phone screen.
    private val portraitFrame = CameraIntrinsics(fx = 500f, fy = 500f, cx = 240f, cy = 320f, width = 480, height = 640)

    @Test
    fun `scale covers the view with the larger axis ratio`() {
        // max(1080/480 = 2.25, 2400/640 = 3.75): height binds, the sides overflow.
        assertEquals(3.75f, CameraFillCenter.scale(480, 640, 1080, 2400)!!, 1e-6f)
        // Landscape frame on a portrait view: max(1080/640, 1920/480 = 4) = 4.
        assertEquals(4f, CameraFillCenter.scale(640, 480, 1080, 1920)!!, 1e-6f)
    }

    @Test
    fun `intrinsics are rescaled to cover then centre-cropped to the view`() {
        // Cover 1800x2400 (x3.75), crop (1800-1080)/2 = 360 off each side.
        val shown = CameraFillCenter.intrinsics(portraitFrame, 1080, 2400)!!
        assertEquals(1875f, shown.fx, 1e-3f)
        assertEquals(1875f, shown.fy, 1e-3f)
        assertEquals(240f * 3.75f - 360f, shown.cx, 1e-3f)
        assertEquals(320f * 3.75f, shown.cy, 1e-3f)
        assertEquals(1080, shown.width)
        assertEquals(2400, shown.height)
    }

    @Test
    fun `projection is the library projection of the cropped intrinsics on the full view`() {
        val expected = ProjectionMatrix.buildFrom(CameraFillCenter.intrinsics(portraitFrame, 1080, 2400)!!)
        assertArrayEquals(expected, CameraFillCenter.projection(portraitFrame, 1080, 2400), 0f)
    }

    @Test
    fun `a matching aspect is the identity crop`() {
        val shown = CameraFillCenter.intrinsics(portraitFrame, 480, 640)!!
        assertEquals(portraitFrame, shown)
        assertArrayEquals(floatArrayOf(0.3f, 0.7f), CameraFillCenter.viewToFrameNormalized(0.3f, 0.7f, 480, 640, 480, 640), 1e-6f)
    }

    @Test
    fun `artwork projection stays registered with the preview within half a view pixel`() {
        // Odd overflow (and a decentred lens) exercises the integer crop's rounding bound.
        val cases = listOf(
            Triple(portraitFrame, 1080, 2400),
            Triple(CameraIntrinsics(510f, 505f, 318f, 236f, 640, 480), 1080, 1920),
            Triple(CameraIntrinsics(1400f, 1400f, 955f, 545f, 1920, 1080), 1080, 2340),
            Triple(CameraIntrinsics(620f, 618f, 241f, 322f, 481, 643), 1081, 2401),
            Triple(CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480), 2400, 1080),
        )
        val points = listOf(
            floatArrayOf(0f, 0f, -2f),
            floatArrayOf(0.3f, -0.2f, -1.5f),
            floatArrayOf(-0.4f, 0.5f, -3f),
            floatArrayOf(0.05f, 0.9f, -4f),
        )
        for ((frame, viewW, viewH) in cases) {
            val frameProj = ProjectionMatrix.buildFrom(frame)
            val viewProj = CameraFillCenter.projection(frame, viewW, viewH)!!
            val s = CameraFillCenter.scale(frame.width, frame.height, viewW, viewH)!!
            val offX = (viewW - frame.width * s) / 2f
            val offY = (viewH - frame.height * s) / 2f
            for (p in points) {
                val inFrame = pixelOf(frameProj, p, frame.width, frame.height)
                // What PreviewView shows: the frame pixel scaled by s and centred (exact float).
                val previewX = inFrame[0] * s + offX
                val previewY = inFrame[1] * s + offY
                val drawn = pixelOf(viewProj, p, viewW, viewH)
                assertEquals("x for $frame on ${viewW}x$viewH", previewX, drawn[0], 0.5f)
                assertEquals("y for $frame on ${viewW}x$viewH", previewY, drawn[1], 0.5f)
            }
        }
    }

    @Test
    fun `screen taps map into the full frame through the same crop`() {
        // The 1080-px-wide view shows frame columns 96..384 of 480 (360/3.75 = 96 cut per side).
        val centre = CameraFillCenter.viewToFrameNormalized(0.5f, 0.5f, 480, 640, 1080, 2400)!!
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), centre, 1e-6f)
        val topLeft = CameraFillCenter.viewToFrameNormalized(0f, 0f, 480, 640, 1080, 2400)!!
        assertArrayEquals(floatArrayOf(96f / 480f, 0f), topLeft, 1e-6f)
        val bottomRight = CameraFillCenter.viewToFrameNormalized(1f, 1f, 480, 640, 1080, 2400)!!
        assertArrayEquals(floatArrayOf(384f / 480f, 1f), bottomRight, 1e-6f)
    }

    @Test
    fun `a tap on a drawn point resolves to that point's frame pixel`() {
        val frame = CameraIntrinsics(1400f, 1400f, 955f, 545f, 1920, 1080)
        val viewW = 1080
        val viewH = 2340
        val p = floatArrayOf(0.1f, -0.05f, -2f)
        val drawn = pixelOf(CameraFillCenter.projection(frame, viewW, viewH)!!, p, viewW, viewH)
        val tapped = CameraFillCenter.viewToFrameNormalized(
            drawn[0] / viewW, drawn[1] / viewH, frame.width, frame.height, viewW, viewH,
        )!!
        val expected = pixelOf(ProjectionMatrix.buildFrom(frame), p, frame.width, frame.height)
        val s = CameraFillCenter.scale(frame.width, frame.height, viewW, viewH)!!
        // Within the half-view-pixel crop rounding, expressed in frame pixels.
        assertEquals(expected[0], tapped[0] * frame.width, 0.51f / s)
        assertEquals(expected[1], tapped[1] * frame.height, 0.51f / s)
    }

    @Test
    fun `field of view narrows on the cropped axis only`() {
        val fov = CameraFillCenter.fieldOfViewDegrees(portraitFrame, 1080, 2400)!!
        val frameH = Math.toDegrees(2.0 * atan(480.0 / (2.0 * 500.0))).toFloat()
        val frameV = Math.toDegrees(2.0 * atan(640.0 / (2.0 * 500.0))).toFloat()
        assertEquals(Math.toDegrees(2.0 * atan(1080.0 / (2.0 * 1875.0))).toFloat(), fov[0], 1e-3f)
        assertTrue(fov[0] < frameH)
        assertEquals(frameV, fov[1], 1e-3f) // the binding axis keeps the whole frame
    }

    @Test
    fun `screen intrinsics use the covering scale with the fit helper's axis swap`() {
        // Sensor-orientation 4000x3000 on a portrait 1080x2400 view: shown as 3000x4000,
        // cover scale max(1080/3000, 2400/4000) = 0.6 (fit would be 0.36).
        val fill = CameraFillCenter.screenIntrinsics(3000f, 3100f, 4000, 3000, 1080, 2400)
        assertArrayEquals(floatArrayOf(3100f * 0.6f, 3000f * 0.6f, 540f, 1200f), fill, 1e-3f)
        val fit = ScreenIntrinsics.fitCenter(3000f, 3100f, 4000, 3000, 1080, 2400)
        assertArrayEquals(floatArrayOf(3100f * 0.36f, 3000f * 0.36f, 540f, 1200f), fit, 1e-3f)
        // Same aspect: fill and fit agree.
        assertArrayEquals(
            ScreenIntrinsics.fitCenter(3000f, 3000f, 4000, 3000, 2000, 1500),
            CameraFillCenter.screenIntrinsics(3000f, 3000f, 4000, 3000, 2000, 1500),
            1e-3f,
        )
        val fallback = CameraFillCenter.screenIntrinsicsFallback(1080, 2400)
        assertTrue(fallback.all { it.isFinite() && it > 0f })
        assertTrue(fallback[0] > ScreenIntrinsics.fallback(1080, 2400)[0])
    }

    @Test
    fun `unset sizes and unusable input return null rather than a guess`() {
        assertNull(CameraFillCenter.scale(0, 640, 1080, 2400))
        assertNull(CameraFillCenter.scale(480, 640, 1080, 0))
        assertNull(CameraFillCenter.intrinsics(portraitFrame, 0, 2400))
        assertNull(CameraFillCenter.intrinsics(CameraIntrinsics.UNKNOWN, 1080, 2400))
        assertNull(CameraFillCenter.projection(portraitFrame, 1080, 0))
        assertNull(CameraFillCenter.fieldOfViewDegrees(portraitFrame, 0, 0))
        assertNull(CameraFillCenter.viewToFrameNormalized(Float.NaN, 0.5f, 480, 640, 1080, 2400))
        assertNull(CameraFillCenter.viewToFrameNormalized(0.5f, 0.5f, 480, 640, 0, 2400))
        assertNotNull(CameraFillCenter.viewToFrameNormalized(0.5f, 0.5f, 480, 640, 1, 1))
    }

    /** Pixel (top-left origin) of eye-space point [p] through column-major [proj] on a w×h image. */
    private fun pixelOf(proj: FloatArray, p: FloatArray, w: Int, h: Int): FloatArray {
        val x = proj[0] * p[0] + proj[4] * p[1] + proj[8] * p[2] + proj[12]
        val y = proj[1] * p[0] + proj[5] * p[1] + proj[9] * p[2] + proj[13]
        val wc = proj[3] * p[0] + proj[7] * p[1] + proj[11] * p[2] + proj[15]
        return floatArrayOf((x / wc + 1f) * 0.5f * w, (1f - y / wc) * 0.5f * h)
    }
}
