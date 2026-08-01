package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPLEMENTATION.md **0.3** — `MetricMarks.glViewToCvDisplay` at 0/90/180/270°.
 *
 * The expected matrices here are **hand-written literals**, not products computed with the same
 * `R_z` the implementation builds. That distinction is the whole value of the file: deriving the
 * expectation from a `rotateZ` helper would compare the implementation against itself and pass
 * with a transposed rotation or a flipped sign, which is precisely the error Phase 0 exists to fix
 * and the error `PlaneMarksObliquityTest` caught an earlier version of itself making.
 *
 * The direction under test, from the capture path's own pixel mapping at `rotationNeeded = 90`
 * (`(u,v) -> (rawH - v, u)`, `fx`/`fy` swapped): a sensor ray `d_s` becomes `(-d_s.y, d_s.x, 1)`,
 * i.e. `R_z(+90) d_s`. So display = `R_z(+deg)` · sensor, pre-multiplied.
 */
class MetricMarksDisplayViewTest {

    private companion object {
        const val EPS = 1e-6f

        /**
         * A deliberately asymmetric GL view: every element distinct, no zeros in the rotation
         * block, non-zero translation. A symmetric or identity-like fixture would let a transpose
         * or a row/column mix-up pass unnoticed.
         *
         * Column-major: element (row, col) is at `[col * 4 + row]`.
         */
        val GL_VIEW = floatArrayOf(
            1f, 2f, 3f, 0f,       // column 0
            4f, 5f, 6f, 0f,       // column 1
            7f, 8f, 9f, 0f,       // column 2
            10f, 11f, 12f, 1f,    // column 3
        )

        /**
         * `glViewToCv(GL_VIEW)` — negate rows 1 and 2. Written out rather than computed so this
         * file does not inherit a bug in `glViewToCv` into every expectation below.
         */
        val CV = floatArrayOf(
            1f, -2f, -3f, 0f,
            4f, -5f, -6f, 0f,
            7f, -8f, -9f, 0f,
            10f, -11f, -12f, 1f,
        )
    }

    private fun at(m: FloatArray, row: Int, col: Int) = m[col * 4 + row]

    @Test
    fun `zero degrees is exactly glViewToCv`() {
        assertArrayEquals(CV, MetricMarks.glViewToCvDisplay(GL_VIEW, 0), EPS)
        assertArrayEquals(
            MetricMarks.glViewToCv(GL_VIEW), MetricMarks.glViewToCvDisplay(GL_VIEW, 0), EPS,
        )
    }

    /**
     * R_z(90) = [[0,-1],[1,0]] on rows 0/1: new row 0 = -old row 1, new row 1 = old row 0.
     * Hand-applied to [CV] column by column.
     */
    @Test
    fun `ninety degrees matches the hand-computed matrix`() {
        val expected = floatArrayOf(
            2f, 1f, -3f, 0f,        // col 0: (-(-2), 1)
            5f, 4f, -6f, 0f,        // col 1: (-(-5), 4)
            8f, 7f, -9f, 0f,        // col 2: (-(-8), 7)
            11f, 10f, -12f, 1f,     // col 3: (-(-11), 10)
        )
        assertArrayEquals(expected, MetricMarks.glViewToCvDisplay(GL_VIEW, 90), EPS)
    }

    /** R_z(180): both rows negated. */
    @Test
    fun `one hundred eighty degrees matches the hand-computed matrix`() {
        val expected = floatArrayOf(
            -1f, 2f, -3f, 0f,
            -4f, 5f, -6f, 0f,
            -7f, 8f, -9f, 0f,
            -10f, 11f, -12f, 1f,
        )
        assertArrayEquals(expected, MetricMarks.glViewToCvDisplay(GL_VIEW, 180), EPS)
    }

    /** R_z(270) = [[0,1],[-1,0]]: new row 0 = old row 1, new row 1 = -old row 0. */
    @Test
    fun `two hundred seventy degrees matches the hand-computed matrix`() {
        val expected = floatArrayOf(
            -2f, -1f, -3f, 0f,
            -5f, -4f, -6f, 0f,
            -8f, -7f, -9f, 0f,
            -11f, -10f, -12f, 1f,
        )
        assertArrayEquals(expected, MetricMarks.glViewToCvDisplay(GL_VIEW, 270), EPS)
    }

    /**
     * The rotation must touch the camera X/Y axes only. Row 2 carries depth; a rotation about the
     * optical axis that changed it would be a different (and wrong) transform, and every recovered
     * depth would shift.
     */
    @Test
    fun `row two is never touched by any rotation`() {
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val out = MetricMarks.glViewToCvDisplay(GL_VIEW, deg)
            for (col in 0 until 4) {
                assertEquals(
                    "row 2 col $col changed at ${deg}deg",
                    at(CV, 2, col), at(out, 2, col), EPS,
                )
                assertEquals(
                    "row 3 col $col changed at ${deg}deg",
                    at(CV, 3, col), at(out, 3, col), EPS,
                )
            }
        }
    }

    /**
     * Four 90° steps return to the start. Weak on its own — a round trip is insensitive to the
     * rotation's *sign*, which is exactly the error most likely here — so it is kept only as a
     * consistency check alongside the literal matrices above, never as a substitute for them.
     */
    @Test
    fun `four quarter turns compose back to identity`() {
        var m = MetricMarks.glViewToCv(GL_VIEW)
        repeat(4) { m = MetricMarks.glViewToCvDisplay(cvToGl(m), 90) }
        assertArrayEquals(CV, m, 1e-5f)
    }

    /** Undo [MetricMarks.glViewToCv] so the round trip above can re-enter the public API. */
    private fun cvToGl(cv: FloatArray): FloatArray {
        val v = cv.copyOf()
        for (col in 0 until 4) {
            v[col * 4 + 1] = -v[col * 4 + 1]
            v[col * 4 + 2] = -v[col * 4 + 2]
        }
        return v
    }

    /** Negative and over-360 inputs normalize rather than throwing or silently mis-rotating. */
    @Test
    fun `rotation is normalized into zero to 360`() {
        assertArrayEquals(
            MetricMarks.glViewToCvDisplay(GL_VIEW, 90),
            MetricMarks.glViewToCvDisplay(GL_VIEW, -270), EPS,
        )
        assertArrayEquals(
            MetricMarks.glViewToCvDisplay(GL_VIEW, 180),
            MetricMarks.glViewToCvDisplay(GL_VIEW, 540), EPS,
        )
    }

    /**
     * A non-multiple of 90 cannot come from `rotationNeeded` and means the caller computed
     * something else. Failing loudly beats rotating the whole wall map by 37°.
     */
    @Test
    fun `a non-quadrant rotation is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MetricMarks.glViewToCvDisplay(GL_VIEW, 37)
        }
    }

    /**
     * The exactness that motivates the integer sine/cosine literals: a `Math.cos(PI/2)` would leave
     * 6.1e-17 in the off-diagonal, and the zeros asserted above would be "almost zero" forever.
     */
    @Test
    fun `quadrant rotations are exact, not floating point approximations`() {
        val out = MetricMarks.glViewToCvDisplay(GL_VIEW, 90)
        // col 0 row 0 is exactly -(-2) == 2, bit for bit.
        assertTrue("expected an exact value, got ${at(out, 0, 0)}", at(out, 0, 0) == 2.0f)
        assertTrue("expected an exact value, got ${at(out, 1, 0)}", at(out, 1, 0) == 1.0f)
    }
}
