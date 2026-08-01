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

    /**
     * The test above uses a pure translation for all three operands, so `inverse(V)·pnp·fpAnchor` is
     * order-independent for that input and a composition written in any order passes it. Use a
     * rotation and a translation that do not commute so the ORDER of the three factors is actually
     * pinned — this is the composition PlaneMarks' documented rotation-convention question turns on,
     * and it had no test that could see a swap.
     */
    @Test fun `composeCorrected pins the order of its three factors`() {
        // 90 deg about Z, no translation.
        val rotZ = floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        val v = rotZ                       // world -> camera is a pure rotation
        val pnp = identity()               // camera -> fingerprint world is identity
        val fpAnchor = trans(1f, 0f, 0f)   // anchor sits +1 along the fingerprint frame's X

        // inverse(V)·pnp·fpAnchor = rotZ⁻¹ · I · trans(1,0,0). rotZ⁻¹ maps +X to -Y, so the composed
        // anchor sits at (0, -1, 0). A composition in any other order does not land there.
        val r = PoseFusion.composeCorrected(vCurrent = v, pnpMat = pnp, fpAnchor = fpAnchor)
        assertEquals(0f, r[12], 1e-4f)
        assertEquals(-1f, r[13], 1e-4f)
        assertEquals(0f, r[14], 1e-4f)
    }

    /**
     * `IMPLEMENTATION.md` **0.6** — `composeCorrected` under convention B.
     *
     * The finding that settles it: `vCurrent` is `Camera.getViewMatrix`, which ARCore documents as
     * *"incorporates the display orientation … equivalent to
     * `camera.getDisplayOrientedPose().inverse().asMatrix()`"*. So the LIVE side of this
     * composition was always in the display frame, while capture stored its 3D points in the
     * sensor frame (`camera.pose.inverse()`). The two sides never agreed; Phase 0 moves capture to
     * display and they now do. `composeCorrected` itself therefore needs no change — this pins that
     * conclusion rather than asserting it in a comment.
     *
     * Deliberately **not** a round trip. `IMPLEMENTATION.md` 0.6 calls that out: a round trip is
     * order- and sign-insensitive and would pass under either convention, which is exactly how this
     * defect survived. Instead: feed a `pnpMat` carrying a residual `R_z` — the pre-Phase-0
     * situation — and assert the error lands in the output, at full size and in a known direction.
     */
    @Test fun `a residual rotation between the pnp and live frames lands in the anchor`() {
        val rotZ90 = floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        val fpAnchor = trans(1f, 0f, 0f)

        // Frames agree (post-Phase-0): pnp in the same display frame as vCurrent.
        val matched = PoseFusion.composeCorrected(identity(), identity(), fpAnchor)
        assertEquals("anchor must sit where the fingerprint put it", 1f, matched[12], 1e-4f)
        assertEquals(0f, matched[13], 1e-4f)

        // Frames disagree by R_z(90) (pre-Phase-0): the anchor swings a quarter turn, a full metre
        // at this radius. Not a subtle bias — which is why "the overlay limps" was the symptom.
        val mismatched = PoseFusion.composeCorrected(identity(), rotZ90, fpAnchor)
        assertEquals(0f, mismatched[12], 1e-4f)
        assertEquals(1f, mismatched[13], 1e-4f)

        val dx = matched[12] - mismatched[12]
        val dy = matched[13] - mismatched[13]
        assertEquals(
            "a 90deg frame mismatch must displace a 1 m anchor by sqrt(2) m",
            kotlin.math.sqrt(2f), kotlin.math.sqrt(dx * dx + dy * dy), 1e-4f,
        )
    }

    /**
     * The rotation enters through `pnpMat` only. If a future change also rotated `vCurrent`, the
     * two would cancel and the mismatch above would silently stop being detectable — so pin that
     * rotating BOTH is the identity case, distinguishing "consistent" from "both wrong the same
     * way" would-be fixes.
     */
    @Test fun `rotating both sides together cancels, as a consistent convention must`() {
        val rotZ90 = floatArrayOf(0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
        val fpAnchor = trans(1f, 0f, 0f)
        val both = PoseFusion.composeCorrected(rotZ90, rotZ90, fpAnchor)
        val neither = PoseFusion.composeCorrected(identity(), identity(), fpAnchor)
        for (i in 0 until 16) {
            assertEquals("element $i", neither[i], both[i], 1e-4f)
        }
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

    /**
     * The teleological claim: the further along the painting, the harder the overlay locks. This is
     * the behaviour that was inert while ArRenderer pinned confGlobal at 1f — both ends of the range
     * produced the identical correction.
     */
    @Test fun `corroboration scales correction strength`() {
        // Same relock, same inlier ratio; only the corroboration differs.
        val bare = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 0f)
        val painted = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        assertTrue(
            "a corroborated wall should pull harder than a bare one: bare=${bare[12]} painted=${painted[12]}",
            painted[12] > bare[12],
        )
        // And the floor bounds how much: CONF_FLOOR=0.5 means full corroboration is exactly 2x.
        assertEquals(2f, painted[12] / bare[12], 1e-3f)
    }

    /**
     * Phase 5a moved the per-attempt decay off the painting-progress channel and onto a separate
     * corroboration-confidence channel, which is what now feeds `confGlobal`. The reason the split
     * matters is a TRANSIENT, not a steady state: a few bad frames used to decay the signal by 0.9
     * per reloc tick, and at a 60 ms tick that suppressed correction strength for seconds after the
     * wall came back. A steady-state comparison cannot see that; this simulates the dip.
     *
     * The contract being pinned: a confidence dip may SLOW the correction, never reverse it.
     */
    @Test fun `a confidence dip slows the correction but never reverses it`() {
        val f = PoseFusion()
        val backbone = trans(0f, 0f, 0f)
        // Warm relock at full confidence.
        f.currentAnchor(backbone, identity(),
            reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        val afterGood = f.currentAnchor(backbone, identity(),
            reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 2f), identity(), confGlobal = 1f)[12]

        // Three ticks of a decayed reading (0.9^1..0.9^3), same relock quality throughout.
        var last = afterGood
        for ((i, conf) in listOf(0.9f, 0.81f, 0.729f).withIndex()) {
            val out = f.currentAnchor(backbone, identity(),
                reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 3f + i), identity(),
                confGlobal = conf)[12]
            assertTrue("a dip must not push the overlay backwards: was $last now $out", out >= last)
            last = out
        }
        assertTrue("the correction must still be advancing toward the fix, got $last", last > afterGood)
    }

    /**
     * `getCorroborationConfidence` returns a NEGATIVE sentinel when nothing has been measured yet,
     * which is a different state from "measured and found nothing". ArRenderer maps it to 0f. Pin
     * that 0f behaves as the conservative floor rather than freezing the overlay — otherwise a
     * never-corroborated wall (no design registered at all) would stop correcting entirely.
     */
    @Test fun `unmeasured corroboration mapped to zero still corrects at the floor`() {
        val unmeasured = PoseFusion().currentAnchor(trans(0f, 0f, 0f), identity(),
            reloc(trans(10f, 0f, 0f), inliers = 60f, matches = 100f, seq = 1f), identity(),
            confGlobal = (-1f).coerceAtLeast(0f))
        assertTrue("an unmeasured wall must still correct, got ${unmeasured[12]}", unmeasured[12] > 0f)
    }

    @Test fun `corroboration outside 0-1 is clamped, not extrapolated`() {
        val full = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 1f)
        val over = PoseFusion().currentAnchor(trans(0f,0f,0f), identity(),
            reloc(trans(10f,0f,0f), inliers = 60f, matches = 100f, seq = 1f), identity(), confGlobal = 5f)
        assertEquals(full[12], over[12], 1e-4f)
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
