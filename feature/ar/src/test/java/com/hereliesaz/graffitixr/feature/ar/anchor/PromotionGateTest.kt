package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IMPLEMENTATION.md` **3.1 / 3.2** — the promotion gate, tested where it can be tested.
 *
 * This predicate guards the only mechanism in the engine that permanently mutates the reloc
 * fingerprint. A bad promotion is not a dropped frame; it is written into the map and compounds. So
 * the tests here are not about coverage — they are the reason the predicate was moved to Kotlin at
 * all, and `CorroborationTranslitTest`'s sibling assertions hold the C++ to the same decisions.
 */
class PromotionGateTest {

    private companion object {
        /** Comfortably above MIN_INLIER_SPREAD, so the match half is what a case is testing. */
        const val WIDE = 0.5f
    }

    // -------------------------------------------------------------------------------------
    // The match half — 3.1, behaviour-preserving port
    // -------------------------------------------------------------------------------------

    /**
     * The bootstrap case, and the reason the flat `inliers >= 20` was replaced. A wall whose marks
     * are half painted over is exactly when self-grow is needed and exactly when 20 raw inliers is
     * out of reach; a strong ratio has to qualify at a lower count or the fingerprint can only grow
     * when it least needs to.
     */
    @Test
    fun `a strong ratio qualifies below the big-lock count`() {
        assertTrue(PromotionGate.trusted(inliers = 12, matches = 15, inlierSpread = WIDE))
        assertEquals("12/15 = 0.8, above the ratio bar", 0.8f, 12f / 15f, 1e-6f)
    }

    @Test
    fun `a big lock qualifies regardless of ratio`() {
        // 20/100 is a terrible ratio; the absolute count is the override, as it shipped.
        assertTrue(PromotionGate.trusted(inliers = 20, matches = 100, inlierSpread = WIDE))
    }

    @Test
    fun `below the absolute inlier floor nothing qualifies`() {
        // 9/9 is a perfect ratio and must still be refused: at that count the ratio is PnP noise.
        assertTrue("the ratio really is perfect", 9f / 9f >= PromotionGate.MIN_INLIER_RATIO)
        assertFalse(PromotionGate.trusted(inliers = 9, matches = 9, inlierSpread = WIDE))
    }

    @Test
    fun `a weak ratio is refused at any count below the big lock`() {
        assertFalse(PromotionGate.trusted(inliers = 19, matches = 40, inlierSpread = WIDE))
        assertTrue("...but the same ratio passes once the count reaches the override",
            PromotionGate.trusted(inliers = 20, matches = 40, inlierSpread = WIDE))
    }

    @Test
    fun `zero or negative matches cannot divide`() {
        assertFalse(PromotionGate.trusted(inliers = 15, matches = 0, inlierSpread = WIDE))
        assertFalse(PromotionGate.trusted(inliers = 15, matches = -1, inlierSpread = WIDE))
    }

    // -------------------------------------------------------------------------------------
    // The spread half — 3.2
    // -------------------------------------------------------------------------------------

    /**
     * The failure 3.2 exists for, stated as a test: a tight cluster with a *perfect* inlier ratio.
     *
     * This is the case the match half cannot see — everything in the cluster agrees with everything
     * else, so the ratio is 1.0 and reads as maximum confidence, while the pose's rotation is barely
     * constrained. If this test ever passes, the spread term has stopped doing its only job.
     */
    @Test
    fun `a perfect ratio in one corner is refused`() {
        val corner = FloatArray(60) { i -> if (i % 2 == 0) 40f + (i % 7) else 30f + (i % 5) }
        val spread = PromotionGate.spreadOf(corner, frameW = 1920f, frameH = 1080f)
        assertTrue("the fixture must actually be clustered, got $spread",
            spread < PromotionGate.MIN_INLIER_SPREAD)
        assertTrue("the match half alone would have said yes",
            PromotionGate.matchTrusted(inliers = 30, matches = 30))
        assertFalse(
            "a clustered inlier set must not authorise a permanent map mutation",
            PromotionGate.trusted(inliers = 30, matches = 30, inlierSpread = spread),
        )
    }

    @Test
    fun `the same lock spread across the frame is accepted`() {
        val spanning = floatArrayOf(
            100f, 100f, 1800f, 120f, 150f, 950f, 1750f, 980f, 900f, 500f,
        )
        val spread = PromotionGate.spreadOf(spanning, frameW = 1920f, frameH = 1080f)
        assertTrue("the fixture must actually span, got $spread", spread > 0.5f)
        assertTrue(PromotionGate.trusted(inliers = 30, matches = 30, inlierSpread = spread))
    }

    /**
     * Not-measured must **fail** here, which is the opposite of `SearchRadius`'s convention, and the
     * asymmetry is the point. There an unmeasured drift reading must not narrow a search, because a
     * too-tight search reads as "the wall does not corroborate" and is unrecoverable-looking. Here an
     * unmeasured spread must not authorise a permanent mutation. Both are "fail toward the
     * recoverable outcome"; recoverable happens to lie in opposite directions.
     */
    @Test
    fun `an unmeasured spread refuses, unlike an unmeasured drift reading`() {
        assertFalse(PromotionGate.trusted(inliers = 30, matches = 30,
            inlierSpread = PromotionGate.NOT_MEASURED))
        assertFalse(PromotionGate.trusted(inliers = 30, matches = 30, inlierSpread = Float.NaN))
        // ...and the sentinel is negative for the usual reason: 0 is a real, and maximally bad, value.
        assertEquals(-1f, PromotionGate.NOT_MEASURED, 0f)
        assertFalse("every inlier on one pixel is the worst case, not the absent case",
            PromotionGate.spreadOk(0f))
    }

    /** The two halves must be independent, or a sweep of one silently moves the other. */
    @Test
    fun `spread and match are independent gates`() {
        // Good spread, bad match.
        assertFalse(PromotionGate.trusted(inliers = 5, matches = 5, inlierSpread = WIDE))
        // Good match, bad spread.
        assertFalse(PromotionGate.trusted(inliers = 30, matches = 30, inlierSpread = 0.001f))
        // Both good.
        assertTrue(PromotionGate.trusted(inliers = 30, matches = 30, inlierSpread = WIDE))
    }

    // -------------------------------------------------------------------------------------
    // spreadOf
    // -------------------------------------------------------------------------------------

    @Test
    fun `spread is the bounding box as a fraction of frame area`() {
        // A box spanning exactly half the width and half the height is a quarter of the area.
        val pts = floatArrayOf(0f, 0f, 960f, 540f)
        assertEquals(0.25f, PromotionGate.spreadOf(pts, 1920f, 1080f), 1e-5f)
    }

    /**
     * Two tight clusters at opposite corners score a full frame. That is a real over-report and it is
     * accepted rather than fixed: both clusters together *do* condition the rotation well, which is
     * what the term is actually asking about. The case that must never be missed is the single
     * compact cluster, and a bounding box cannot miss that.
     */
    @Test
    fun `two distant clusters score high, which is correct rather than a false pass`() {
        val pts = floatArrayOf(10f, 10f, 12f, 14f, 1900f, 1060f, 1898f, 1058f)
        assertTrue(PromotionGate.spreadOf(pts, 1920f, 1080f) > 0.9f)
    }

    @Test
    fun `no points and a degenerate frame report not-measured, not zero`() {
        assertEquals(-1f, PromotionGate.spreadOf(FloatArray(0), 1920f, 1080f), 0f)
        assertEquals(-1f, PromotionGate.spreadOf(floatArrayOf(1f, 1f), 0f, 1080f), 0f)
        assertEquals(-1f, PromotionGate.spreadOf(floatArrayOf(1f, 1f), 1920f, Float.NaN), 0f)
    }

    /**
     * A single NaN point must not blow the box out to the whole frame. That would turn the gate into
     * a rubber stamp on exactly the degenerate input it exists to catch — the precise inversion of
     * its purpose, and silent.
     */
    @Test
    fun `a non-finite point does not inflate the box`() {
        val pts = floatArrayOf(100f, 100f, Float.NaN, 50f, 140f, 130f)
        val spread = PromotionGate.spreadOf(pts, 1920f, 1080f)
        assertTrue("got $spread", spread.isFinite() && spread < 0.01f)
        // All points non-finite is absence, not a zero-area box.
        assertEquals(-1f,
            PromotionGate.spreadOf(floatArrayOf(Float.NaN, Float.NaN), 1920f, 1080f), 0f)
    }

    @Test
    fun `spread is clamped so an out-of-frame inlier cannot exceed one`() {
        val pts = floatArrayOf(-500f, -500f, 3000f, 2000f)
        assertEquals(1f, PromotionGate.spreadOf(pts, 1920f, 1080f), 1e-6f)
    }

    @Test
    fun `the pre-registered priors are the ones PARAMETERS documents`() {
        // Literal, so a silent edit to a value E5 is waiting on shows up as a test change.
        assertEquals(20, PromotionGate.BIG_LOCK_INLIERS)
        assertEquals(10, PromotionGate.MIN_INLIERS)
        assertEquals(0.6f, PromotionGate.MIN_INLIER_RATIO, 0f)
        assertEquals(0.04f, PromotionGate.MIN_INLIER_SPREAD, 0f)
    }
}
