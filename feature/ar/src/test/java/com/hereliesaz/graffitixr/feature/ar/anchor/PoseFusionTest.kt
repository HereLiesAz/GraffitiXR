package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseFusionTest {
    private fun identity() = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
    private fun trans(x: Float, y: Float, z: Float) =
        floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,y,z,1f)

    /** reloc payload with vCurrent=I, fpAnchor=I so composeCorrected(reloc)=target. */
    private fun reloc(target: FloatArray, inliers: Float, matches: Float, seq: Float) =
        FloatArray(19).also {
            System.arraycopy(target, 0, it, 0, 16); it[16] = inliers; it[17] = matches; it[18] = seq
        }

    @Test fun `composeCorrected with V=pnp and fpAnchor=I yields identity`() {
        val v = trans(2f, 0f, 0f)
        val r = PoseFusion.composeCorrected(vCurrent = v, pnpMat = v, fpAnchor = identity())
        identity().forEachIndexed { i, e -> assertEquals(e, r[i], 1e-4f) }
    }

    @Test fun `blend alpha 0 returns current, alpha 1 returns target`() {
        val cur = trans(0f,0f,0f); val tgt = trans(10f,0f,0f)
        assertEquals(0f, PoseFusion.blend(cur, tgt, 0f)[12], 1e-4f)
        assertEquals(10f, PoseFusion.blend(cur, tgt, 1f)[12], 1e-4f)
        assertEquals(5f, PoseFusion.blend(cur, tgt, 0.5f)[12], 1e-4f)
    }

    @Test fun `returns backbone when no new reloc result`() {
        val f = PoseFusion()
        val backbone = trans(1f,1f,1f)
        val out = f.currentAnchor(backbone, identity(), FloatArray(19), identity(), confGlobal = 1f)
        backbone.forEachIndexed { i, e -> assertEquals(e, out[i], 1e-4f) }
    }

    @Test fun `ignores low-inlier-ratio snaps`() {
        val f = PoseFusion()
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(99f,0f,0f), inliers = 1f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertEquals(0f, out[12], 1e-3f)
    }

    @Test fun `first confident snap hard-snaps to correction`() {
        val f = PoseFusion()
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertEquals(10f, out[12], 1e-3f)
    }

    @Test fun `moderate-confidence first snap partially corrects`() {
        val f = PoseFusion()
        // ratio 0.6: above MIN_INLIER_RATIO but below COLD_SNAP_INLIER_RATIO -> smooth, not snap.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertTrue("expected partial move, got ${out[12]}", out[12] > 0f && out[12] < 10f)
    }

    @Test fun `depth-off (confGlobal 0) still corrects via inlier ratio`() {
        val f = PoseFusion()
        // The old design multiplied alpha by confGlobal, so conf=0 froze the overlay. The floor fixes it.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 0f)
        assertTrue("expected non-zero correction with depth off, got ${out[12]}", out[12] > 0f)
    }

    @Test fun `correction persists and stays world-locked between snaps`() {
        val f = PoseFusion()
        // Confident cold snap establishes D = +10x at backbone origin.
        f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        // No new snap, but ARCore's frame drifts the backbone by +1z: D must still apply.
        val out = f.currentAnchor(trans(0f,0f,1f), identity(), FloatArray(19), identity(), confGlobal = 1f)
        assertEquals(10f, out[12], 1e-3f)
        assertEquals(1f, out[14], 1e-3f)
    }

    @Test fun `confident relock that diverges far hard-snaps (pocket case)`() {
        val f = PoseFusion()
        f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        // New confident snap puts the anchor 10m away -> beyond COLD_SNAP_DIST_M -> instant relock.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(20f,0f,0f), inliers = 90f, matches = 100f, seq = 2f), identity(), confGlobal = 1f)
        assertEquals(20f, out[12], 1e-3f)
    }

    @Test fun `small confident relock smooths instead of teleporting`() {
        val f = PoseFusion()
        f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 90f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        // 5cm move (< COLD_SNAP_DIST_M) -> not cold -> smoothed, lands just past 10, not snapped to 10.05.
        val out = f.currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10.05f,0f,0f), inliers = 90f, matches = 100f, seq = 2f), identity(), confGlobal = 1f)
        assertTrue("expected smoothed move, got ${out[12]}", out[12] > 10f && out[12] < 10.05f)
    }
}
