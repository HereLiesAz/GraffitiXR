package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * `IMPLEMENTATION.md` **0.9** — is `PoseFusion.composeCorrected` actually in consistent frames?
 *
 * 0.6 confirmed the factor ORDER with non-commuting rotations, and said explicitly that it made no
 * claim about the frames. This file makes that claim testable, without a device, using an invariant
 * that needs no external ground truth:
 *
 * > **If nothing has drifted since capture, the correction must be the identity.**
 *
 * `PoseFusion.currentAnchor` computes `newD = corrected · inverse(backbone)` and draws
 * `D ∘ backbone`. So when ARCore has not drifted, `backbone` is already the right answer, the
 * relocalizer sees exactly what the current view predicts, and `corrected` must come back equal to
 * `backbone`. Any residual IS the composition error, and its shape says which factor is missing.
 *
 * ## Why this is not the circular test it could have been
 *
 * The one input that encodes a belief is `pnpMat` — what `solvePnP` would return under zero drift.
 * Rather than assert my own convention, it is built from the repo's OWN shipped bridge:
 *
 *  - `MetricMarks.glViewToCv` is how every fingerprint's capture view is converted, and
 *  - `MetricFingerprintBuilder` back-projects the fingerprint's 3D points through exactly that CV
 *    view, storing `captureAnchorCam = cvView · anchorModel`.
 *
 * So the fingerprint frame IS the display-oriented CV camera frame at capture — not by assumption
 * but by construction, in code that ships and whose fingerprints demonstrably relocalize. Under zero
 * drift a point's live CV camera coordinates are therefore
 * `V_cv(t) · V_cv(capture)⁻¹` applied to its fingerprint coordinates, which is the `pnpMat` below.
 *
 * The honest limit: this validates `composeCorrected` **against the fingerprint builder**. It cannot
 * catch an error the two share. An inconsistency between them is precisely what 0.6's audit alleged,
 * so it is aimed at the right target — but it is not a device.
 */
class ComposeCorrectedFrameTest {

    private companion object {
        /** Rigid pose from a Z-Y-X-ish mix, column-major. Deliberately not axis-aligned. */
        fun pose(rxDeg: Float, ryDeg: Float, tx: Float, ty: Float, tz: Float): FloatArray {
            val rx = Math.toRadians(rxDeg.toDouble())
            val ry = Math.toRadians(ryDeg.toDouble())
            val cx = cos(rx).toFloat(); val sx = sin(rx).toFloat()
            val cy = cos(ry).toFloat(); val sy = sin(ry).toFloat()
            // R = Ry * Rx, written out column-major [col*4 + row].
            val r00 = cy;            val r01 = sy * sx;      val r02 = sy * cx
            val r10 = 0f;            val r11 = cx;           val r12 = -sx
            val r20 = -sy;           val r21 = cy * sx;      val r22 = cy * cx
            return floatArrayOf(
                r00, r10, r20, 0f,
                r01, r11, r21, 0f,
                r02, r12, r22, 0f,
                tx, ty, tz, 1f,
            )
        }

        fun assertMatrixEquals(msg: String, expected: FloatArray, actual: FloatArray, eps: Float) {
            val worst = (0 until 16).maxOf { kotlin.math.abs(expected[it] - actual[it]) }
            assertTrue(
                "$msg\n  worst element error $worst\n  expected ${expected.toList()}\n  actual   ${actual.toList()}",
                worst <= eps,
            )
        }
    }

    /** The capture-time camera pose, the live camera pose, and where the anchor sits. */
    private val vGlCapture = pose(rxDeg = 12f, ryDeg = -25f, tx = 0.3f, ty = 1.4f, tz = -0.2f)
    private val vGlLive = pose(rxDeg = -7f, ryDeg = 40f, tx = -0.6f, ty = 1.5f, tz = 0.9f)
    private val anchorModel = pose(rxDeg = 3f, ryDeg = 18f, tx = 1.1f, ty = 0.4f, tz = -2.5f)

    /**
     * `pnpMat` as the relocalizer would produce it with no drift: the transform taking a point from
     * the fingerprint frame (= the capture CV camera frame) to the live CV camera frame.
     *
     * Built through `MetricMarks.glViewToCv`, the repo's own converter, rather than by writing
     * `diag(1,-1,-1)` here — so the test inherits the shipped convention instead of asserting one.
     */
    private fun zeroDriftPnp(): FloatArray {
        val cvCapture = MetricMarks.glViewToCv(vGlCapture)
        val cvLive = MetricMarks.glViewToCv(vGlLive)
        return PoseMath.multiply(cvLive, PoseMath.rigidInverse(cvCapture))
    }

    /**
     * The diagnosis. Under zero drift the current composition must return the anchor unchanged.
     *
     * This is the assertion that found 0.9's defect. Against the SHIPPED composition
     * (`inv(V) · pnp · fpAnchor`, world-frame anchor, no axis conversion) it failed by 2.92 — not a
     * rounding artefact but a wholly different pose. It now runs against the repaired one.
     */
    @Test
    fun `zero drift corrects to the anchor itself`() {
        val corrected = PoseFusion.composeCorrected(
            vGlLive, zeroDriftPnp(), PoseMath.multiply(MetricMarks.glViewToCv(vGlCapture), anchorModel),
        )
        assertMatrixEquals(
            "zero drift must correct to the anchor itself; a residual here IS a frame defect",
            anchorModel, corrected, 1e-4f,
        )
    }

    /**
     * The proposed corrected composition, stated as an independent hypothesis and checked against
     * the same invariant.
     *
     * Two things are wrong with the shipped form, and the derivation collapses the repair into one
     * substitution plus one conversion:
     *
     *  - `pnpMat` is CV-convention, so it needs the `D = diag(1,-1,-1)` axis conversion;
     *  - its domain is the FINGERPRINT frame — the capture CV camera — while `fpAnchor` is a
     *    world-space model matrix, so a capture-view factor is needed between them.
     *
     * Since `captureAnchorCam = V_cv(capture) · anchorModel = D · V_gl(capture) · anchorModel`, the
     * second factor supplies its own `D` and the repair is:
     *
     * ```
     * corrected = inv(V_gl(t)) · [D · pnp] · captureAnchorCam
     * ```
     *
     * `D · pnp` is exactly `MetricMarks.glViewToCv(pnp)`, and `captureAnchorCam` is a quantity
     * Phase 2 already builds, persists and partitions with. So the fix needs no new geometry: it
     * swaps `fpAnchor` for something the project already computes for another reason, and applies
     * one existing converter.
     */
    @Test
    fun `the corrected composition satisfies the invariant`() {
        val captureAnchorCam =
            PoseMath.multiply(MetricMarks.glViewToCv(vGlCapture), anchorModel)
        val corrected = PoseMath.multiply(
            PoseMath.rigidInverse(vGlLive),
            PoseMath.multiply(MetricMarks.glViewToCv(zeroDriftPnp()), captureAnchorCam),
        )
        assertMatrixEquals(
            "the corrected form must return the anchor unchanged under zero drift",
            anchorModel, corrected, 1e-4f,
        )
    }

    /**
     * **Conjugating is wrong, and I wrote it that way first.**
     *
     * `D · pnp · D` is the textbook conversion of a *transform* between conventions, and reaching
     * for it here is the natural move — but `captureAnchorCam` already carries a `D` of its own, so
     * the trailing factor applies it twice and lands 4.29 away from the anchor. The shipped form is
     * 2.92 away; the double conversion is *worse than the defect it was meant to fix*.
     *
     * That is §8.3's warning about signs arriving in person, and it is pinned here because the
     * mistake survives inspection: both forms are one plausible sentence away from correct, and
     * only the invariant tells them apart.
     */
    @Test
    fun `converting pnp on both sides is worse than not converting it at all`() {
        val captureAnchorCam =
            PoseMath.multiply(MetricMarks.glViewToCv(vGlCapture), anchorModel)
        val doubled = PoseMath.multiply(
            PoseMath.rigidInverse(vGlLive),
            PoseMath.multiply(cvToGlTransform(zeroDriftPnp()), captureAnchorCam),
        )
        // The ORIGINAL defective composition, written out rather than called: composeCorrected has
        // since been fixed, so there is no longer a function that produces it.
        val originalDefect = PoseMath.multiply(
            PoseMath.multiply(PoseMath.rigidInverse(vGlLive), zeroDriftPnp()), anchorModel,
        )
        val worstDoubled = (0 until 16).maxOf { kotlin.math.abs(anchorModel[it] - doubled[it]) }
        val worstOriginal = (0 until 16).maxOf { kotlin.math.abs(anchorModel[it] - originalDefect[it]) }
        assertTrue("the double conversion must not satisfy the invariant", worstDoubled > 1e-3f)
        assertTrue(
            "double-converting ($worstDoubled) should be worse than the original defect " +
                "($worstOriginal) — that is why it is pinned",
            worstDoubled > worstOriginal,
        )
    }

    /**
     * `D · m · D` with `D = diag(1,-1,-1)` — the conversion of a TRANSFORM between the two
     * conventions, as opposed to the conversion of a view matrix.
     *
     * The left factor reuses the shipped `glViewToCv` (which negates rows 1 and 2) so the test stays
     * tied to the repo's convention rather than restating it. The right factor negates COLUMNS 1 and
     * 2, which has no shipped helper because nothing else needed one — it is written out here, and
     * `the converter negates rows, so the column pass is its mirror` checks the pair against each
     * other rather than against my arithmetic.
     */
    private fun cvToGlTransform(m: FloatArray): FloatArray {
        val dm = MetricMarks.glViewToCv(m)                 // D · m  (rows 1,2 negated)
        val out = dm.copyOf()
        for (row in 0 until 4) {                           // (D · m) · D  (cols 1,2 negated)
            out[1 * 4 + row] = -out[1 * 4 + row]
            out[2 * 4 + row] = -out[2 * 4 + row]
        }
        return out
    }

    /**
     * The column pass above has no shipped counterpart, so it is checked against the one that does:
     * `D` is symmetric, so negating the rows of `mᵀ` must equal negating the columns of `m`.
     */
    @Test
    fun `the converter negates rows, so the column pass is its mirror`() {
        val m = pose(rxDeg = 21f, ryDeg = -13f, tx = 0.7f, ty = -0.2f, tz = 1.9f)
        val rowsNegated = MetricMarks.glViewToCv(m)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                val expected = if (row == 1 || row == 2) -m[col * 4 + row] else m[col * 4 + row]
                assertEquals("($row,$col)", expected, rowsNegated[col * 4 + row], 0f)
            }
        }
    }
}
