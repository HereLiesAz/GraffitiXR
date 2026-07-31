package com.hereliesaz.graffitixr.feature.ar.anchor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVALUATION.md **E0** — the display-rotation convention, characterized.
 *
 * The claim in `PlaneMarks`' CAUTION block — pixels and intrinsics are rotated to display
 * orientation, the view matrix is not, so the recovered depths skew with obliquity — sat in a
 * comment for several development cycles with no test behind it. `PlaneMarksTest` has only
 * fronto-parallel and world-translated cases, so the defect it warns about has never been exercised
 * once. This file exercises it.
 *
 * The geometry, from `backProject`'s own expression `t = (n·p)/(n·d)`: a rotation preserves the dot
 * product, so `n·p` is identical in either frame and the ENTIRE error lives in the denominator —
 * `n_sensor·d` where `n_display·d` was meant. Each recovered point therefore lies on the correct ray
 * at the wrong depth, by a factor that varies across the image. That is a **non-rigid** distortion,
 * which is the reason it matters: a rigid one would be absorbed by the reloc PnP, and this is not.
 *
 * Two kinds of test here, and the distinction is the point:
 *
 *  - [frames agreeing recovers the truth exactly] is a permanent correctness test. It pins that
 *    `backProject` itself is right when it is handed a consistent frame. It must pass forever.
 *  - The characterization tests below record what the CURRENT mismatch costs. They pass today
 *    BECAUSE the defect is present. **When Phase 0 lands, they must be inverted** — flip the
 *    assertions to `< TOLERANCE_MM` and they become the regression guard. Do not "fix" them by
 *    loosening a bound; a suddenly-passing characterization test means the bug moved, not that it
 *    was fixed.
 *
 * Expected magnitudes are in `PAPER.md` §8.1 and were derived independently of this code.
 */
class PlaneMarksObliquityTest {

    private companion object {
        // Display-oriented intrinsics for a 1080x1920 portrait frame (fx/fy already swapped by
        // ArRenderer's rotationNeeded==90 branch).
        const val FX = 1400f
        const val FY = 1400f
        const val CX = 540f
        const val CY = 960f
        const val W = 1080
        const val H = 1920

        /** Wall distance along its own normal, metres. */
        const val DIST_M = 2f

        /** What "recovered the truth" means for a synthetic case with no noise. */
        const val TOLERANCE_MM = 1f
    }

    /** Column-major 4x4 rotation about the camera's optical axis (Z), as a CV-convention view. */
    private fun rzView(deg: Float): FloatArray {
        val c = cos(Math.toRadians(deg.toDouble())).toFloat()
        val s = sin(Math.toRadians(deg.toDouble())).toFloat()
        return floatArrayOf(
            c, s, 0f, 0f,
            -s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun identityView() = rzView(0f)

    /** A grid of pixels over the central 80% of the frame — the region features actually land in. */
    private fun samplePixels(step: Int = 8): List<PlaneMarks.Pixel> = buildList {
        for (i in 0 until step) {
            val v = 0.1f * H + 0.8f * H * i / (step - 1)
            for (j in 0 until step) {
                val u = 0.1f * W + 0.8f * W * j / (step - 1)
                add(PlaneMarks.Pixel(u, v))
            }
        }
    }

    /** Wall normal in the CAMERA frame, tilted [obliquityDeg] about the camera's Y axis. */
    private fun wallNormalCam(obliquityDeg: Float): FloatArray {
        val a = Math.toRadians(obliquityDeg.toDouble())
        return floatArrayOf(sin(a).toFloat(), 0f, cos(a).toFloat())
    }

    /**
     * Ground truth, computed in CLOSED FORM rather than by calling the code under test. For each
     * sampled pixel, walk its camera ray to the wall analytically: `t = (n·p)/(n·d)`, point = `t·d`.
     *
     * Deriving truth independently is what makes the control tests real. Comparing `backProject`
     * against a second `backProject` call would be a tautology that passes no matter how wrong the
     * implementation is.
     */
    private fun analyticTruth(obliquityDeg: Float, pixels: List<PlaneMarks.Pixel>): Map<Int, FloatArray> {
        val n = wallNormalCam(obliquityDeg)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)
        val nDotP = n[0] * p[0] + n[1] * p[1] + n[2] * p[2]
        val out = HashMap<Int, FloatArray>(pixels.size)
        for ((i, px) in pixels.withIndex()) {
            val dx = (px.u - CX) / FX
            val dy = (px.v - CY) / FY
            val nDotD = n[0] * dx + n[1] * dy + n[2]
            if (abs(nDotD) < 1e-6f) continue
            val t = nDotP / nDotD
            if (t < 0.1f || t > 10f) continue // backProject's own trust range
            out[i] = floatArrayOf(t * dx, t * dy, t)
        }
        return out
    }

    /**
     * Per-pixel distance in mm between what `backProject` returns when the plane arrives through
     * [viewForPlane] and the closed-form truth. The rays are always in the frame the pixels were
     * measured in, so [viewForPlane] is exactly the shipped mismatch: identity means the plane is in
     * the same frame as the rays (correct), a rotation means it is not.
     */
    private fun errorsMm(obliquityDeg: Float, viewForPlane: FloatArray): List<Float> {
        val pixels = samplePixels()
        val n = wallNormalCam(obliquityDeg)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)
        val truth = analyticTruth(obliquityDeg, pixels)

        val actual = PlaneMarks.backProject(pixels, viewForPlane, p, n, FX, FY, CX, CY)
        val out = ArrayList<Float>(actual.count)
        for ((slot, src) in actual.kept.withIndex()) {
            val t = truth[src] ?: continue
            val o = slot * 3
            val dx = actual.pointsCam[o] - t[0]
            val dy = actual.pointsCam[o + 1] - t[1]
            val dz = actual.pointsCam[o + 2] - t[2]
            out.add(hypot(hypot(dx, dy), dz) * 1000f)
        }
        return out
    }

    private fun meanMm(e: List<Float>) = if (e.isEmpty()) 0f else e.sum() / e.size

    // ---------------------------------------------------------------------------------------
    // Permanent correctness — must pass before AND after Phase 0.
    // ---------------------------------------------------------------------------------------

    /**
     * When the rays and the plane are in the same frame, `backProject` matches the closed form
     * exactly. This is the control: it proves any error measured below comes from the frame
     * mismatch and not from the back-projection itself.
     */
    @Test
    fun `frames agreeing recovers the truth exactly`() {
        for (obliquity in floatArrayOf(0f, 20f, 40f, 60f)) {
            val e = errorsMm(obliquity, identityView())
            assertTrue(
                "consistent frames must be exact at ${obliquity}deg, was ${meanMm(e)}mm",
                e.all { it < TOLERANCE_MM },
            )
        }
    }

    /**
     * A round trip through the projection: project a known 3D point on the wall to a pixel by hand,
     * back-project that pixel, and land on the point you started from. Guards the intrinsics
     * handling independently of the frame question.
     */
    @Test
    fun `back-projection inverts a hand-computed projection`() {
        val n = wallNormalCam(30f)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)
        // A point on the plane: start from a ray, walk it to the plane analytically.
        val d = floatArrayOf(0.15f, -0.08f, 1f)
        val t = (n[0] * p[0] + n[1] * p[1] + n[2] * p[2]) / (n[0] * d[0] + n[1] * d[1] + n[2] * d[2])
        val truth = floatArrayOf(t * d[0], t * d[1], t * d[2])
        val pixel = PlaneMarks.Pixel(truth[0] / truth[2] * FX + CX, truth[1] / truth[2] * FY + CY)

        val r = PlaneMarks.backProject(listOf(pixel), identityView(), p, n, FX, FY, CX, CY)
        assertEquals(1, r.count)
        for (i in 0..2) assertEquals(truth[i], r.pointsCam[i], TOLERANCE_MM / 1000f)
    }

    /**
     * The reason the defect went unnoticed for so long, stated as a test. A plane normal of
     * (0,0,±1) is invariant under a rotation about the optical axis, so the mismatch cancels
     * completely when the camera is square to the wall — which is how anyone would first test it.
     */
    @Test
    fun `head-on wall is immune to the rotation mismatch`() {
        for (rotation in floatArrayOf(90f, 180f, 270f)) {
            val e = errorsMm(obliquityDeg = 0f, viewForPlane = rzView(rotation))
            assertTrue(
                "a head-on wall must be exact under ${rotation}deg, was ${meanMm(e)}mm",
                e.all { it < TOLERANCE_MM },
            )
        }
    }

    /**
     * Landscape sets `rotationNeeded == 0`, so there is no mismatch to make — the whole defect is
     * inactive at every obliquity. This is what makes the hypothesis testable on a shipped build by
     * rotating the phone (EVALUATION.md E0b), and it must keep holding after Phase 0.
     */
    @Test
    fun `zero rotation is exact at every obliquity`() {
        for (obliquity in floatArrayOf(0f, 20f, 40f, 60f)) {
            val e = errorsMm(obliquity, rzView(0f))
            assertTrue(
                "rotationNeeded==0 must be exact at ${obliquity}deg, was ${meanMm(e)}mm",
                e.all { it < TOLERANCE_MM },
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Characterization of the CURRENT defect. INVERT THESE WHEN PHASE 0 LANDS.
    // ---------------------------------------------------------------------------------------

    /**
     * The headline number. Portrait on a typical phone gives `rotationNeeded == 90`, and at a
     * modest 20 degrees off-square the recovered marks are hundreds of millimetres out.
     *
     * PHASE 0: change to `assertTrue(mean < TOLERANCE_MM)`.
     */
    @Test
    fun `CHARACTERIZATION portrait rotation skews depths badly off-square`() {
        val mean20 = meanMm(errorsMm(20f, rzView(90f)))
        val mean40 = meanMm(errorsMm(40f, rzView(90f)))
        // PAPER.md 8.1 predicts ~267mm and ~834mm. Assert the order of magnitude, not the decimal —
        // the sampling grid here is coarser than the one used for the table.
        assertTrue("expected hundreds of mm at 20deg, got $mean20", mean20 in 100f..600f)
        assertTrue("expected ~metre-scale at 40deg, got $mean40", mean40 in 400f..2000f)
        assertTrue("error must grow with obliquity", mean40 > mean20)
    }

    /**
     * Holding the phone the other way round gives `rotationNeeded == 180`, which is a different
     * rotation but still a mismatch — so "just use landscape" only helps in ONE of the two landscape
     * orientations. Worth pinning: it is the kind of asymmetry a fix can easily half-solve.
     *
     * PHASE 0: change to `assertTrue(mean < TOLERANCE_MM)`.
     */
    @Test
    fun `CHARACTERIZATION half-turn rotation is also a mismatch`() {
        val mean = meanMm(errorsMm(20f, rzView(180f)))
        assertTrue("expected a real error at 180deg, got $mean", mean > 100f)
    }

    /**
     * The distortion is NON-RIGID — the depth scale factor varies across the image. This is the
     * property that makes it damaging rather than merely offset: a rigid error would be absorbed by
     * the reloc PnP's pose solve, and this one cannot be, which is why it shows up as a low inlier
     * ratio alongside a healthy match count.
     *
     * PHASE 0: the spread collapses to zero along with the error, so this becomes redundant with
     * the headline test above and can be deleted.
     */
    @Test
    fun `CHARACTERIZATION the distortion is non-rigid, not a constant offset`() {
        val e = errorsMm(40f, rzView(90f))
        val mean = meanMm(e)
        val spread = e.maxOrNull()!! - e.minOrNull()!!
        assertTrue(
            "a rigid offset would have near-zero spread; spread=$spread mean=$mean",
            spread > 0.5f * mean,
        )
    }

    /**
     * Mis-scaled depths fall outside `backProject`'s 0.1-10 m trust range and are dropped, so the
     * mismatch does not only distort points — it silently loses them. That is the mechanism behind
     * the "found N features but only M landed on the wall surface" refusal, and it means a user hits
     * a capture failure rather than a visibly wrong overlay.
     *
     * PHASE 0: retention returns to 100% and this becomes an equality assertion.
     */
    @Test
    fun `CHARACTERIZATION steep obliquity silently discards marks`() {
        val pixels = samplePixels()
        val n = wallNormalCam(60f)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)
        val kept = PlaneMarks.backProject(pixels, rzView(90f), p, n, FX, FY, CX, CY).count
        val keptTruth = PlaneMarks.backProject(pixels, identityView(), p, n, FX, FY, CX, CY).count
        assertTrue("control should keep everything, kept $keptTruth of ${pixels.size}", keptTruth == pixels.size)
        assertTrue("expected the mismatch to drop marks at 60deg, kept $kept of ${pixels.size}", kept < keptTruth)
    }

    /**
     * Error is monotone in obliquity — zero square-on, worse the further off you stand. Pins the
     * SHAPE of the defect rather than any single magnitude, which is what E0 on device compares
     * against. A device result that is flat, or worst at 0 degrees, falsifies the diagnosis in
     * PAPER.md 8 rather than merely disagreeing about a constant.
     *
     * PHASE 0: the curve flattens to zero; assert every entry `< TOLERANCE_MM`.
     */
    @Test
    fun `CHARACTERIZATION error grows monotonically with obliquity`() {
        val curve = floatArrayOf(0f, 10f, 20f, 30f, 40f).map { meanMm(errorsMm(it, rzView(90f))) }
        assertEquals("square-on must be exact", 0f, curve[0], TOLERANCE_MM)
        for (i in 1 until curve.size) {
            assertTrue("error must not decrease as obliquity grows: $curve", curve[i] > curve[i - 1])
        }
        assertTrue("10deg should already be measurable, was ${curve[1]}", abs(curve[1]) > 10f)
    }
}
