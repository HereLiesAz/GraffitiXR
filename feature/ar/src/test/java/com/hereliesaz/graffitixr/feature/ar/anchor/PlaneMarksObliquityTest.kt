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
 * Ground truth is built FORWARD (see [samplesOn]): points are placed on the wall in its own basis
 * and projected to pixels, so the truth path never evaluates the ray-plane intersection that
 * `backProject` is being tested on. An earlier version derived truth from the pixel with the same
 * `t = (n·p)/(n·d)` the implementation uses, which made the control tests tautologies that would
 * have passed with a sign flip or a swapped numerator.
 *
 * Magnitudes here will NOT match `PAPER.md` §8.1's table. That table samples uniformly in PIXEL
 * space over a 40x40 grid; this samples uniformly on the WALL, which weights oblique views
 * differently. Both are correct measurements of the same defect; only the sampling differs. The
 * assertions below are therefore ranges and orderings, not the paper's decimals.
 */
class PlaneMarksObliquityTest {

    private companion object {
        // Display-oriented intrinsics for a 1080x1920 portrait frame (fx/fy already swapped by
        // ArRenderer's rotationNeeded==90 branch). Deliberately DIFFERENT. Equal focal lengths make an fx/fy swap — the exact thing the
        // display rotation does to the intrinsics — undetectable by every test in this file.
        const val FX = 1400f
        const val FY = 1250f
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

    /** Wall normal in the CAMERA frame, tilted [obliquityDeg] about the camera's Y axis. */
    private fun wallNormalCam(obliquityDeg: Float): FloatArray {
        val a = Math.toRadians(obliquityDeg.toDouble())
        return floatArrayOf(sin(a).toFloat(), 0f, cos(a).toFloat())
    }

    /** A 3D point paired with the pixel it projects to. */
    private class Sample(val world: FloatArray, val pixel: PlaneMarks.Pixel)

    /**
     * Ground truth, built **forward**: parameterize the wall by its own two in-plane basis vectors,
     * place points on it, and project each through the pinhole model to get its pixel.
     *
     * The direction matters. An earlier version of this file computed truth by re-deriving
     * `t = (n·p)/(n·d)` from the pixel — which is `backProject`'s own loop retyped, so the controls
     * compared the implementation against itself and would have passed with a sign flip, a swapped
     * numerator, or a wrong ray. Going forward shares only the pinhole projection, and shares it as
     * the *inverse* operation, so an error in the unprojection cannot be mirrored into the truth.
     */
    private fun samplesOn(obliquityDeg: Float, steps: Int = 25, span: Float = 4f): List<Sample> {
        val a = Math.toRadians(obliquityDeg.toDouble())
        val n = wallNormalCam(obliquityDeg)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)
        // Orthonormal in-plane basis: e1 lies in the XZ plane perpendicular to n, e2 is world +Y.
        val e1 = floatArrayOf(cos(a).toFloat(), 0f, -sin(a).toFloat())
        val e2 = floatArrayOf(0f, 1f, 0f)

        val out = ArrayList<Sample>()
        for (i in 0 until steps) {
            val s = -span + 2f * span * i / (steps - 1)
            for (j in 0 until steps) {
                val t = -span + 2f * span * j / (steps - 1)
                val wx = p[0] + s * e1[0] + t * e2[0]
                val wy = p[1] + s * e1[1] + t * e2[1]
                val wz = p[2] + s * e1[2] + t * e2[2]
                if (wz < 0.1f || wz > 10f) continue                      // behind, or past the trust range
                val u = wx / wz * FX + CX
                val v = wy / wz * FY + CY
                if (u < 0.05f * W || u > 0.95f * W) continue             // off-frame
                if (v < 0.05f * H || v > 0.95f * H) continue
                out.add(Sample(floatArrayOf(wx, wy, wz), PlaneMarks.Pixel(u, v)))
            }
        }
        return out
    }

    /**
     * Per-point distance in mm between what `backProject` returns when the plane arrives through
     * [viewForPlane] and the forward-constructed truth. The rays are always in the frame the pixels
     * were measured in, so [viewForPlane] IS the shipped mismatch: identity means the plane is in
     * that same frame (correct), a rotation means it is not.
     */
    private fun errorsMm(obliquityDeg: Float, viewForPlane: FloatArray): List<Float> {
        val samples = samplesOn(obliquityDeg)
        val n = wallNormalCam(obliquityDeg)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)

        val actual = PlaneMarks.backProject(
            samples.map { it.pixel }, viewForPlane, p, n, FX, FY, CX, CY,
        )
        val out = ArrayList<Float>(actual.count)
        for ((slot, src) in actual.kept.withIndex()) {
            val truth = samples[src].world
            val o = slot * 3
            val dx = actual.pointsCam[o] - truth[0]
            val dy = actual.pointsCam[o + 1] - truth[1]
            val dz = actual.pointsCam[o + 2] - truth[2]
            out.add(hypot(hypot(dx, dy), dz) * 1000f)
        }
        return out
    }

    private fun meanMm(e: List<Float>) = if (e.isEmpty()) 0f else e.sum() / e.size

    // ---------------------------------------------------------------------------------------
    // Permanent correctness — must pass before AND after Phase 0.
    // ---------------------------------------------------------------------------------------

    /**
     * When the rays and the plane are in the same frame, `backProject` recovers the forward-
     * constructed points exactly. Two things at once: it proves the unprojection genuinely inverts
     * the projection (FX != FY here, so a focal-length swap fails this), and it establishes that any
     * error measured below comes from the frame mismatch and not from `backProject` itself.
     *
     * This is also the LANDSCAPE case — `rotationNeeded == 0` means no mismatch exists to make — so
     * it doubles as the prediction EVALUATION.md E0b asks you to check by rotating the phone.
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
        val deg = 30f
        val a = Math.toRadians(deg.toDouble())
        val n = wallNormalCam(deg)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)

        // Build the point FORWARD, from the plane's own basis — never from a ray. Walking a ray to
        // the plane would be `backProject`'s own intersection formula, and the assertion would then
        // only confirm the implementation agrees with itself.
        val e1 = floatArrayOf(cos(a).toFloat(), 0f, -sin(a).toFloat())
        val s = 0.31f
        val t = -0.17f
        val truth = floatArrayOf(
            p[0] + s * e1[0],
            p[1] + t,                 // e2 is world +Y
            p[2] + s * e1[2],
        )
        // Sanity: the constructed point really is on the plane (n·(x-p) == 0).
        val onPlane = n[0] * (truth[0] - p[0]) + n[1] * (truth[1] - p[1]) + n[2] * (truth[2] - p[2])
        assertEquals("fixture must lie on the wall", 0f, onPlane, 1e-5f)

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
        // Wall-uniform sampling gives ~328mm and ~1148mm here; PAPER.md 8.1's pixel-uniform 40x40
        // grid gives 267mm and 834mm for the same defect. Assert the band both live in, so neither
        // sampling choice is silently baked in as the definition of the bug.
        assertTrue("expected hundreds of mm at 20deg, got $mean20", mean20 in 150f..700f)
        assertTrue("expected ~metre-scale at 40deg, got $mean40", mean40 in 500f..2500f)
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
        assertTrue("...but bounded; a runaway here means the model changed, got $mean", mean < 700f)
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
        val samples = samplesOn(60f)
        val pixels = samples.map { it.pixel }
        val n = wallNormalCam(60f)
        val p = floatArrayOf(n[0] * DIST_M, n[1] * DIST_M, n[2] * DIST_M)
        val kept = PlaneMarks.backProject(pixels, rzView(90f), p, n, FX, FY, CX, CY).count
        val keptTruth = PlaneMarks.backProject(pixels, identityView(), p, n, FX, FY, CX, CY).count
        assertEquals("control must keep every forward-constructed point", pixels.size, keptTruth)
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
