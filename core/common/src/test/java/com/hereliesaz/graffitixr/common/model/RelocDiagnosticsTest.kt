package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
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
