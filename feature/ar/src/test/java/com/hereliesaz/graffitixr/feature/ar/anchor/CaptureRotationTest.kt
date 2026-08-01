package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test Phase 0 shipped without, and the gap the audit named: **nothing drove a pixel through the
 * actual capture code.**
 *
 * Phase 0's entire correctness argument is one claim — that the capture path's bitmap rotation and
 * intrinsics rotation together turn a sensor-frame camera ray `d_s` into `R_z(+rotationNeeded)·d_s`,
 * and therefore that `MetricMarks.glViewToCvDisplay` must pre-multiply by `R_z(+θ)`. Every test
 * written for it asserted properties *of the converter*, taking that claim as given. So a
 * misreading of `ArRenderer`'s intrinsics branches or `ArViewModel`'s `postRotate` would have been
 * mirrored into the converter, into the tests, and into the paper, and every one of them would have
 * agreed with the others while being wrong together.
 *
 * This closes the loop, end to end and in the only direction that can fail independently:
 *
 *  1. take a known 3D point in the **sensor** camera frame;
 *  2. project it with the **raw sensor** intrinsics — plain pinhole, no rotation anywhere;
 *  3. move that pixel the way the **bitmap rotation** moves it ([CaptureRotation.rotatePixel]);
 *  4. unproject with the **rotated** intrinsics that `ArRenderer` actually builds
 *     ([CaptureRotation.rotateIntrinsics], now the shipped code path);
 *  5. compare the resulting ray against `R_z(+θ)·d_s`, with `R_z` written out here from
 *     `cos`/`sin` — **not** taken from `MetricMarks.rotateZ`, which is the thing under test.
 *
 * If the convention is backwards, step 5 disagrees and this fails. No other test in the suite can
 * say that.
 */
class CaptureRotationTest {

    private companion object {
        // A deliberately non-square raw sensor frame with an OFF-CENTRE principal point and
        // DIFFERENT focal lengths. Every one of those matters: a square frame hides an axis swap, a
        // centred principal point hides the `rawH - cy` remap, and equal focals hide the fx/fy swap
        // entirely — which is the single most likely thing to get wrong here.
        const val RAW_W = 1920f
        const val RAW_H = 1080f
        const val FX = 1400f
        const val FY = 1310f
        const val CX = 950f      // not RAW_W/2
        const val CY = 520f      // not RAW_H/2
        const val EPS = 1e-3f
    }

    /** Pinhole projection in the sensor frame. No rotation involved anywhere. */
    private fun projectSensor(x: Float, y: Float, z: Float) =
        floatArrayOf(x / z * FX + CX, y / z * FY + CY)

    /** `R_z(deg)` applied to a 3-vector, written from trig so it shares nothing with the impl. */
    private fun rzApply(deg: Int, v: FloatArray): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return floatArrayOf(c * v[0] - s * v[1], s * v[0] + c * v[1], v[2])
    }

    /** Direction cosines, so two rays are comparable regardless of scale. */
    private fun normalize(v: FloatArray): FloatArray {
        val n = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    /**
     * The whole claim, at every quadrant and over a spread of points including strongly off-axis
     * ones — where a wrong sign is largest and a centred point would show nothing.
     */
    @Test
    fun `the capture path rotates a sensor ray by exactly R_z of plus rotationNeeded`() {
        val points = listOf(
            floatArrayOf(0.4f, 0.25f, 2f),
            floatArrayOf(-0.6f, 0.1f, 1.5f),
            floatArrayOf(0.15f, -0.5f, 3f),
            floatArrayOf(-0.35f, -0.3f, 2.5f),
            floatArrayOf(0f, 0f, 2f),          // on-axis: must be invariant
        )
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val r = CaptureRotation.rotateIntrinsics(FX, FY, CX, CY, RAW_W, RAW_H, deg)
            for (p in points) {
                // The sensor-frame ray, straight from the point.
                val dSensor = normalize(floatArrayOf(p[0] / p[2], p[1] / p[2], 1f))

                // ...and the ray the capture path actually produces for the same physical point.
                val px = projectSensor(p[0], p[1], p[2])
                val rot = CaptureRotation.rotatePixel(px[0], px[1], RAW_W, RAW_H, deg)
                val dDisplay = normalize(
                    floatArrayOf((rot[0] - r[2]) / r[0], (rot[1] - r[3]) / r[1], 1f),
                )

                val expected = normalize(rzApply(deg, dSensor))
                for (i in 0..2) {
                    assertEquals(
                        "deg=$deg point=${p.toList()} component $i: " +
                            "capture path gave ${dDisplay.toList()}, R_z(+$deg) predicts ${expected.toList()}",
                        expected[i], dDisplay[i], EPS,
                    )
                }
            }
        }
    }

    /**
     * The negative control. If the convention were `R_z(−θ)` — the sign this could most plausibly
     * have been — the quarter turns would disagree measurably. Without this, a test that somehow
     * compared a vector with itself would still pass.
     */
    @Test
    fun `the opposite sign is measurably wrong at the quarter turns`() {
        val p = floatArrayOf(0.4f, 0.25f, 2f)
        for (deg in intArrayOf(90, 270)) {
            val r = CaptureRotation.rotateIntrinsics(FX, FY, CX, CY, RAW_W, RAW_H, deg)
            val px = projectSensor(p[0], p[1], p[2])
            val rot = CaptureRotation.rotatePixel(px[0], px[1], RAW_W, RAW_H, deg)
            val actual = normalize(
                floatArrayOf((rot[0] - r[2]) / r[0], (rot[1] - r[3]) / r[1], 1f),
            )
            val wrong = normalize(rzApply(-deg, normalize(floatArrayOf(p[0] / p[2], p[1] / p[2], 1f))))
            val gap = (0..2).maxOf { abs(actual[it] - wrong[it]) }
            assertTrue("R_z(-$deg) must NOT match the capture path; gap was $gap", gap > 0.05f)
        }
    }

    /** Zero rotation must be the identity in both halves, or every landscape capture is skewed. */
    @Test
    fun `zero rotation leaves intrinsics and pixels untouched`() {
        val r = CaptureRotation.rotateIntrinsics(FX, FY, CX, CY, RAW_W, RAW_H, 0)
        assertEquals(FX, r[0], 0f); assertEquals(FY, r[1], 0f)
        assertEquals(CX, r[2], 0f); assertEquals(CY, r[3], 0f)
        val px = CaptureRotation.rotatePixel(123f, 456f, RAW_W, RAW_H, 0)
        assertEquals(123f, px[0], 0f); assertEquals(456f, px[1], 0f)
    }

    /**
     * The quarter turns swap the focal lengths and the half-turn does not. `FX != FY` here on
     * purpose — with equal focals this assertion is vacuous, and equal focals are exactly what a
     * lazy fixture would use.
     */
    @Test
    fun `quarter turns swap the focal lengths, the half turn does not`() {
        for (deg in intArrayOf(90, 270)) {
            val r = CaptureRotation.rotateIntrinsics(FX, FY, CX, CY, RAW_W, RAW_H, deg)
            assertEquals("deg=$deg", FY, r[0], 0f)
            assertEquals("deg=$deg", FX, r[1], 0f)
        }
        val half = CaptureRotation.rotateIntrinsics(FX, FY, CX, CY, RAW_W, RAW_H, 180)
        assertEquals(FX, half[0], 0f)
        assertEquals(FY, half[1], 0f)
    }

    /** Four quarter turns of a pixel return it to where it started. */
    @Test
    fun `four quarter turns return a pixel to itself`() {
        var u = 700f; var v = 300f
        var w = RAW_W; var h = RAW_H
        repeat(4) {
            val p = CaptureRotation.rotatePixel(u, v, w, h, 90)
            u = p[0]; v = p[1]
            val t = w; w = h; h = t      // the frame's dimensions swap with each quarter turn
        }
        assertEquals(700f, u, EPS)
        assertEquals(300f, v, EPS)
    }
}
