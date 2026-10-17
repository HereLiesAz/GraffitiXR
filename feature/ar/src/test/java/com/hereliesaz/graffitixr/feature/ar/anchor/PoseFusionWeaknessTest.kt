package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.common.model.FusionState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for two weaknesses in how [PoseFusion] trusts relocalization, found by the
 * teleological-SLAM audit. They pin the behaviour AS IT IS TODAY so a fix shows up as a deliberate
 * change to these tests rather than an unnoticed one; each says what the behaviour should become.
 */
class PoseFusionWeaknessTest {
    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private fun trans(x: Float, y: Float, z: Float) =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)
    private fun rotZ(deg: Double): FloatArray {
        val c = cos(Math.toRadians(deg)).toFloat(); val s = sin(Math.toRadians(deg)).toFloat()
        return floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
    /** Capture camera and anchor both at the origin: `V_cv · anchorModel = diag(1,-1,-1)`. */
    private fun captureAtOrigin() =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
    private fun reloc(pnp: FloatArray, inliers: Float, matches: Float, seq: Float) =
        FloatArray(19).also {
            System.arraycopy(pnp, 0, it, 0, 16); it[16] = inliers; it[17] = matches; it[18] = seq
        }

    private fun fused(f: PoseFusion, pnp: FloatArray, inliers: Float, matches: Float, seq: Float) =
        f.currentAnchor(identity(), identity(), reloc(pnp, inliers, matches, seq), captureAtOrigin(), confGlobal = 0f)

    private fun dist(m: FloatArray) = sqrt(m[12] * m[12] + m[13] * m[13] + m[14] * m[14])

    /**
     * WEAKNESS: a relocalization that diverges from the drawn pose, with a low-ish inlier ratio and
     * few inliers (so not a cold snap), is BLENDED instead of refused. Repeated texture (brick,
     * tile, a repeated motif) produces exactly this: ten false fixes a metre away, each blended at
     * `BASE_ALPHA * ratio * CONF_FLOOR` = 0.0625, walk the overlay about 0.48 m toward the twin.
     *
     * SHOULD BECOME: a diverged fix that is not high-confidence is refused (RELOCK_REFUSED) unless
     * several consecutive fixes agree, as the hybrid path already requires. When that lands, change
     * the bounds below to "stays within a few centimetres".
     */
    @Test fun `repeated diverged low-confidence fixes walk the overlay toward a false twin`() {
        val f = PoseFusion()
        // A good lock at the origin: 30 inliers of 30, identity.
        fused(f, identity(), 30f, 30f, 1f)
        assertEquals(FusionState.COLD_SNAP, f.diagnostics().state)

        var out = identity()
        for (seq in 2..11) out = fused(f, trans(1f, 0f, 0f), 10f, 20f, seq.toFloat())

        assertEquals("each is blended, none refused", FusionState.BLENDING, f.diagnostics().state)
        val moved = dist(out)
        // 1 - (1 - 0.0625)^10 = 0.476 m of a 1 m disagreement.
        assertTrue("moved $moved m; a fix refused would leave ~0", moved > 0.3f && moved < 0.7f)
    }

    /**
     * WEAKNESS: fusion decides on counts only. The relocalization payload carries no measure of how
     * the inliers are spread, so 25 inliers clustered in one corner (a well-fitting but badly
     * constrained rotation) are as good as 25 spread across the wall and snap instantly, however
     * far the rotation is from what is drawn.
     *
     * SHOULD BECOME: the payload carries the inlier spread and a cold snap requires it above the
     * promotion floor; a clustered solve blends or is refused. Then this test should assert that.
     */
    @Test fun `a high-ratio fix snaps to any rotation because inlier spread is not an input`() {
        val f = PoseFusion()
        fused(f, identity(), 30f, 30f, 1f)
        val out = fused(f, rotZ(30.0), 25f, 30f, 2f)
        assertEquals(FusionState.COLD_SNAP, f.diagnostics().state)
        // The drawn pose was swung by about the full 30 degrees on the strength of counts alone.
        val turned = abs(Math.toDegrees(Math.atan2(out[1].toDouble(), out[0].toDouble())))
        assertTrue("turned $turned deg", turned > 20.0 && turned < 40.0)
    }
}
