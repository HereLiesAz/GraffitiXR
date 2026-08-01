package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelocDiagnosticsTest {

    /**
     * The native side reports the reject as a raw int and the Kotlin side reads it by ordinal, so the
     * two enums have to stay ordinal-for-ordinal. These are the values in MobileGS::RelocReject.
     */
    @Test
    fun `reject ordinals match the native contract`() {
        assertEquals(0, RelocReject.OK.ordinal)
        assertEquals(1, RelocReject.NO_FINGERPRINT.ordinal)
        assertEquals(2, RelocReject.DISABLED.ordinal)
        assertEquals(3, RelocReject.NO_FEATURES.ordinal)
        assertEquals(4, RelocReject.FEW_MATCHES.ordinal)
        assertEquals(5, RelocReject.PNP_FAILED.ordinal)
        assertEquals(6, RelocReject.FEW_INLIERS.ordinal)
        // UNKNOWN is Kotlin-only: it absorbs any code a future native build reports.
        assertEquals(7, RelocReject.UNKNOWN.ordinal)
    }

    @Test
    fun `inlier ratio is inliers over matches`() {
        assertEquals(0.5f, RelocDiagnostics(RelocReject.OK, matches = 40, inliers = 20).inlierRatio, 1e-4f)
        assertEquals(1f, RelocDiagnostics(RelocReject.OK, matches = 12, inliers = 12).inlierRatio, 1e-4f)
    }

    @Test
    fun `inlier ratio is zero rather than NaN when nothing matched`() {
        assertEquals(0f, RelocDiagnostics(RelocReject.NO_FEATURES).inlierRatio, 1e-4f)
    }

    @Test
    fun `default is the never-attempted state, not a fake success`() {
        assertEquals(RelocReject.UNKNOWN, RelocDiagnostics().reject)
        assertEquals(0, RelocDiagnostics().matches)
        assertEquals(0, RelocDiagnostics().detected)
        // -1, not 0: zero degrees is a legitimate measurement (dead-on square to the wall), so the
        // "rectification wasn't eligible" state needs its own value.
        assertEquals(-1, RelocDiagnostics().obliquityDeg)
        assertEquals(0, RelocDiagnostics().rectifiedCorrespondences)
    }

    /**
     * The backbone counts follow [RelocDiagnostics.obliquityDeg]'s -1 precedent, and they have to.
     *
     * Before Phase 2 there was no partition and a zero backbone was meaningless, so 0 would have
     * been a harmless default. With the partition landed it is the single most important reading
     * these fields ever carry: `F_out` empty means the artwork covers the whole visible wall and
     * reloc has nothing to bootstrap a pose from (`PAPER.md` §5.3, Phase 2's risk section). Had 0
     * doubled as "not measured", the one condition the counters exist to expose would read exactly
     * like a reloc thread that has never run.
     */
    @Test
    fun `unmeasured backbone counts are minus one, never a real zero`() {
        val unmeasured = RelocDiagnostics()
        assertEquals(-1, unmeasured.backboneFeatures)
        assertEquals(-1, unmeasured.backboneMatches)
        assertEquals(-1, unmeasured.backboneInliers)

        // The wall-filling design: measured, and the measurement is zero.
        val wallFilling = RelocDiagnostics(
            RelocReject.FEW_MATCHES, matches = 0, inliers = 0, detected = 1400,
            backboneFeatures = 0, backboneMatches = 0, backboneInliers = 0,
        )
        assertEquals(0, wallFilling.backboneFeatures)
        assertTrue(
            "an empty backbone must not read as an unsampled one",
            wallFilling.backboneFeatures != unmeasured.backboneFeatures,
        )
        assertTrue(wallFilling.backboneMatches != unmeasured.backboneMatches)
        assertTrue(wallFilling.backboneInliers != unmeasured.backboneInliers)
    }

    /**
     * `IMPLEMENTATION.md` 2.11 — the backbone counts are reported *beside* the totals, not in place
     * of them, because the same shortfall means different things. Forty correspondences of which
     * three are backbone is "the frame is looking at the artwork, not at the wall around it";
     * three correspondences of which three are backbone is "the frame is barely seeing the wall at
     * all". A single `matches` column collapses those into one row.
     */
    @Test
    fun `total and backbone counts are separately readable`() {
        val underTheArtwork = RelocDiagnostics(
            RelocReject.OK, matches = 40, inliers = 24, detected = 1400,
            backboneFeatures = 300, backboneMatches = 3, backboneInliers = 2,
        )
        val barelySeeingTheWall = RelocDiagnostics(
            RelocReject.FEW_MATCHES, matches = 3, inliers = 0, detected = 1400,
            backboneFeatures = 300, backboneMatches = 3, backboneInliers = 0,
        )
        assertEquals(
            "the backbone shortfall is identical in both",
            underTheArtwork.backboneMatches, barelySeeingTheWall.backboneMatches,
        )
        assertTrue(
            "and only the total tells them apart",
            underTheArtwork.matches != barelySeeingTheWall.matches,
        )
        assertEquals(40, underTheArtwork.matches)
        assertEquals(3, underTheArtwork.backboneMatches)
        assertEquals(24, underTheArtwork.inliers)
        assertEquals(2, underTheArtwork.backboneInliers)
        // The ratio PoseFusion gates on still uses the TOTAL. Reusing `matches` for the backbone
        // count would have silently changed it.
        assertEquals(24f / 40f, underTheArtwork.inlierRatio, 1e-4f)
    }

    /**
     * A legacy (unpartitioned) fingerprint is the all-backbone case, so it reports its full point
     * count — not zero, and not the sentinel. The native side computes it; this pins the
     * three-way distinction the field has to carry.
     */
    @Test
    fun `legacy all-backbone, empty backbone and unmeasured are three distinct readings`() {
        val legacy = RelocDiagnostics(RelocReject.OK, backboneFeatures = 512)
        val empty = RelocDiagnostics(RelocReject.FEW_MATCHES, backboneFeatures = 0)
        val unmeasured = RelocDiagnostics()
        assertEquals(3, setOf(legacy, empty, unmeasured).map { it.backboneFeatures }.toSet().size)
        assertTrue("a legacy fingerprint is all backbone, not none", legacy.backboneFeatures > 0)
    }

    /**
     * `IMPLEMENTATION.md` **4.8** — the corroboration counts carry the same three-way distinction as
     * the backbone trio, and the middle case is the one the guard exists for.
     *
     * -1 is "no *gated* corroboration attempt has run" — no design placement, or no pose this
     * frame, so the global fallback match measured a different quantity and deliberately did not
     * fill these in. Zero predicted is "the artist is looking away from the design", which is
     * ordinary and self-correcting. Zero matched under a positive predicted is the real alarm: the
     * design is in view and the wall backs none of it. Collapsing any two of those would hide the
     * third.
     */
    @Test
    fun `unmeasured, nothing-in-view and nothing-corroborated are three distinct readings`() {
        val unmeasured = RelocDiagnostics()
        assertEquals(-1, unmeasured.corrobPredicted)
        assertEquals(-1, unmeasured.corrobMatched)

        val lookingAway = RelocDiagnostics(RelocReject.OK, corrobPredicted = 0, corrobMatched = 0)
        val inViewNoAgreement =
            RelocDiagnostics(RelocReject.OK, corrobPredicted = 240, corrobMatched = 0)
        assertEquals(
            "the three states must not share a corrobPredicted value",
            3,
            setOf(unmeasured, lookingAway, inViewNoAgreement).map { it.corrobPredicted }.toSet().size,
        )
        // ...and the two zero-matched states are told apart by the denominator alone, which is the
        // whole reason predicted is published rather than just the ratio.
        assertEquals(lookingAway.corrobMatched, inViewNoAgreement.corrobMatched)
        assertTrue(lookingAway.corrobPredicted != inViewNoAgreement.corrobPredicted)
    }

    /**
     * The float channel's sentinel, which cannot be zero for a reason the int fields do not share:
     * a perfectly tracking pose really does report a near-zero reprojection residual, and a search
     * radius clamped to its floor is a real 4 px. Both are readings this column exists to show.
     */
    @Test
    fun `corroboration float diagnostics default to minus one, and zero is a real reading`() {
        assertEquals(-1f, CorroborationDiagnostics().searchRadiusPx, 0f)
        assertEquals(-1f, CorroborationDiagnostics().relocReprojPx, 0f)
        val perfectLock = CorroborationDiagnostics(searchRadiusPx = 4f, relocReprojPx = 0f)
        assertTrue(
            "a zero reprojection error must not be indistinguishable from not measured",
            perfectLock != CorroborationDiagnostics(),
        )
    }

    /**
     * The same match shortfall means opposite things depending on how much texture the frame had, so
     * the two have to be independently readable.
     */
    @Test
    fun `detected count is independent of the match count`() {
        val starved = RelocDiagnostics(RelocReject.FEW_MATCHES, matches = 3, inliers = 0, detected = 12)
        val misaimed = RelocDiagnostics(RelocReject.FEW_MATCHES, matches = 3, inliers = 0, detected = 1400)
        assertEquals(starved.matches, misaimed.matches)
        assertEquals(12, starved.detected)
        assertEquals(1400, misaimed.detected)
    }
}
