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
 * [PoseFusion] must not be walked or swung by relocalization fixes it has no reason to trust. Found by
 * the teleological-SLAM audit: a diverged fix with a middling inlier ratio used to be BLENDED however
 * often it recurred (repeated texture, a symmetric design), and a clustered solve with a high ratio
 * snapped the drawn pose through any rotation because only counts reached fusion.
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
    private fun reloc(pnp: FloatArray, inliers: Float, matches: Float, seq: Float, spread: Float? = null) =
        FloatArray(if (spread == null) 19 else 36).also {
            System.arraycopy(pnp, 0, it, 0, 16); it[16] = inliers; it[17] = matches; it[18] = seq
            if (spread != null) it[PoseFusion.RELOC_SPREAD_INDEX] = spread
        }

    private fun fused(f: PoseFusion, pnp: FloatArray, inliers: Float, matches: Float, seq: Float, spread: Float? = null) =
        f.currentAnchor(identity(), identity(), reloc(pnp, inliers, matches, seq, spread), captureAtOrigin(), confGlobal = 0f)

    private fun dist(m: FloatArray) = sqrt(m[12] * m[12] + m[13] * m[13] + m[14] * m[14])

    /** Fixes that disagree with each other (a bouncing false match) are refused, not averaged in. */
    @Test fun `inconsistent diverged low-confidence fixes are refused`() {
        val f = PoseFusion()
        fused(f, identity(), 30f, 30f, 1f)
        assertEquals(FusionState.COLD_SNAP, f.diagnostics().state)

        var out = identity()
        for (seq in 2..11) {
            val side = if (seq % 2 == 0) 1f else -1f
            out = fused(f, trans(side, 0f, 0f), 10f, 20f, seq.toFloat())
        }
        assertEquals(FusionState.RELOCK_REFUSED, f.diagnostics().state)
        assertTrue("the overlay moved ${dist(out)} m on fixes that never agreed", dist(out) < 0.01f)
    }

    /**
     * A STABLE false twin is still believed, but only after [PoseFusion.DIVERGED_AGREEMENT]
     * consecutive agreeing fixes, and then blended: ten fixes used to move the overlay about 0.48 m,
     * now about a third of that. (Stopping a perfectly consistent twin is the job of the orientation
     * check and corroboration, not of fusion.)
     */
    @Test fun `a consistent diverged fix needs agreement before it moves the overlay`() {
        val f = PoseFusion()
        fused(f, identity(), 30f, 30f, 1f)

        // The first two agreeing fixes change nothing at all.
        var out = fused(f, trans(1f, 0f, 0f), 10f, 20f, 2f)
        assertEquals(FusionState.RELOCK_REFUSED, f.diagnostics().state)
        assertTrue(dist(out) < 0.001f)
        out = fused(f, trans(1f, 0f, 0f), 10f, 20f, 3f)
        assertEquals(FusionState.RELOCK_REFUSED, f.diagnostics().state)
        assertTrue(dist(out) < 0.001f)
        // The third is believed and blended.
        out = fused(f, trans(1f, 0f, 0f), 10f, 20f, 4f)
        assertEquals(FusionState.BLENDING, f.diagnostics().state)
        assertTrue("moved ${dist(out)} m", dist(out) in 0.03f..0.1f)

        for (seq in 5..11) out = fused(f, trans(1f, 0f, 0f), 10f, 20f, seq.toFloat())
        assertTrue("walked ${dist(out)} m", dist(out) < 0.3f)
    }

    /** A high ratio with the inliers clustered in one corner cannot swing the drawn pose. */
    @Test fun `a clustered high-ratio fix does not snap`() {
        val f = PoseFusion()
        fused(f, identity(), 30f, 30f, 1f, spread = 0.5f)
        val out = fused(f, rotZ(30.0), 25f, 30f, 2f, spread = 0.01f)
        assertEquals(FusionState.RELOCK_REFUSED, f.diagnostics().state)
        val turned = abs(Math.toDegrees(Math.atan2(out[1].toDouble(), out[0].toDouble())))
        assertTrue("turned $turned deg on a clustered solve", turned < 1.0)
    }

    @Test fun `a well-spread high-ratio fix snaps once fixes agree`() {
        val f = PoseFusion()
        fused(f, identity(), 30f, 30f, 1f, spread = 0.5f)
        // Diverged: a lone confident fix holds (mirror/twin protection) ...
        fused(f, rotZ(30.0), 25f, 30f, 2f, spread = 0.3f)
        assertEquals(FusionState.RELOCK_REFUSED, f.diagnostics().state)
        fused(f, rotZ(30.0), 25f, 30f, 3f, spread = 0.3f)
        // ... and DIVERGED_AGREEMENT agreeing fixes snap.
        val out = fused(f, rotZ(30.0), 25f, 30f, 4f, spread = 0.3f)
        assertEquals(FusionState.COLD_SNAP, f.diagnostics().state)
        val turned = abs(Math.toDegrees(Math.atan2(out[1].toDouble(), out[0].toDouble())))
        assertTrue("turned $turned deg", turned > 20.0 && turned < 40.0)
    }

    /** A payload without the spread slot (legacy 19-float result) or with it unmeasured must not block a snap. */
    @Test fun `an unmeasured spread does not block a snap`() {
        val f = PoseFusion()
        fused(f, identity(), 30f, 30f, 1f)
        for (seq in 2..4) fused(f, rotZ(30.0), 25f, 30f, seq.toFloat())
        assertEquals(FusionState.COLD_SNAP, f.diagnostics().state)
        val g = PoseFusion()
        fused(g, identity(), 30f, 30f, 1f, spread = 0.5f)
        for (seq in 2..4) fused(g, rotZ(30.0), 25f, 30f, seq.toFloat(), spread = -1f)
        assertEquals(FusionState.COLD_SNAP, g.diagnostics().state)
    }
}
