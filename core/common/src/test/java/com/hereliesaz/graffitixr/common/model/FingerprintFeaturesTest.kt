package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FingerprintFeatures]: what each choice builds, the cycle, and what survives storage. */
class FingerprintFeaturesTest {

    @Test
    fun `each choice is a detector and whether the close-ups come too`() {
        assertTrue(FingerprintFeatures.SUPERPOINT.superPoint); assertFalse(FingerprintFeatures.SUPERPOINT.closeUps)
        assertFalse(FingerprintFeatures.HALF_SPHERE.superPoint); assertTrue(FingerprintFeatures.HALF_SPHERE.closeUps)
        assertTrue(FingerprintFeatures.BOTH.superPoint); assertTrue(FingerprintFeatures.BOTH.closeUps)
        assertEquals(FingerprintFeatures.BOTH, FingerprintFeatures.DEFAULT)
    }

    @Test
    fun `tapping cycles through all three and wraps`() {
        var f = FingerprintFeatures.SUPERPOINT
        val seen = (1..3).map { f.also { f = FingerprintFeatures.next(it) } }
        assertEquals(FingerprintFeatures.entries.toList(), seen)
        assertEquals(FingerprintFeatures.SUPERPOINT, f)
    }

    @Test
    fun `stored keys round-trip, anything else reads as the default`() {
        for (f in FingerprintFeatures.entries) assertEquals(f, FingerprintFeatures.fromStorageKey(f.storageKey))
        assertEquals(FingerprintFeatures.DEFAULT, FingerprintFeatures.fromStorageKey(null))
        assertEquals(FingerprintFeatures.DEFAULT, FingerprintFeatures.fromStorageKey("orb"))
    }
}
