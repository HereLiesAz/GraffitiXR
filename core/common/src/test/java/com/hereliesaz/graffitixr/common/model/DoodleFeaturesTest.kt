package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DoodleFeatures]: what each choice builds, the cycle, and what survives storage. */
class DoodleFeaturesTest {

    @Test
    fun `each choice is a detector and whether the close-ups come too`() {
        assertTrue(DoodleFeatures.SUPERPOINT.superPoint); assertFalse(DoodleFeatures.SUPERPOINT.closeUps)
        assertFalse(DoodleFeatures.HALF_SPHERE.superPoint); assertTrue(DoodleFeatures.HALF_SPHERE.closeUps)
        assertTrue(DoodleFeatures.BOTH.superPoint); assertTrue(DoodleFeatures.BOTH.closeUps)
        assertEquals(DoodleFeatures.BOTH, DoodleFeatures.DEFAULT)
    }

    @Test
    fun `tapping cycles through all three and wraps`() {
        var f = DoodleFeatures.SUPERPOINT
        val seen = (1..3).map { f.also { f = DoodleFeatures.next(it) } }
        assertEquals(DoodleFeatures.entries.toList(), seen)
        assertEquals(DoodleFeatures.SUPERPOINT, f)
    }

    @Test
    fun `stored keys round-trip, anything else reads as the default`() {
        for (f in DoodleFeatures.entries) assertEquals(f, DoodleFeatures.fromStorageKey(f.storageKey))
        assertEquals(DoodleFeatures.DEFAULT, DoodleFeatures.fromStorageKey(null))
        assertEquals(DoodleFeatures.DEFAULT, DoodleFeatures.fromStorageKey("orb"))
    }
}
