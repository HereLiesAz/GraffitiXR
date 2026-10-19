package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoopRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopNativeOwnershipTest {
    @Test
    fun `ARCore guest peer geometry suppresses ordinary project doodle restore`() {
        assertFalse(
            shouldRestoreLocalArCoreDoodle(
                isArCoreAvailable = true,
                coopRole = CoopRole.GUEST,
                peerSpatialFramePresent = true,
            )
        )
    }

    @Test
    fun `local doodle restore remains enabled outside peer-owned guest session`() {
        assertTrue(
            shouldRestoreLocalArCoreDoodle(
                isArCoreAvailable = true,
                coopRole = CoopRole.NONE,
                peerSpatialFramePresent = false,
            )
        )
        assertTrue(
            shouldRestoreLocalArCoreDoodle(
                isArCoreAvailable = true,
                coopRole = CoopRole.HOST,
                peerSpatialFramePresent = false,
            )
        )
        assertFalse(
            shouldRestoreLocalArCoreDoodle(
                isArCoreAvailable = false,
                coopRole = CoopRole.NONE,
                peerSpatialFramePresent = false,
            )
        )
    }
}
