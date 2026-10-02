package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.model.CoopRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoopNativeOwnershipTest {
    @Test
    fun `ARCore guest peer geometry suppresses ordinary project fingerprint restore`() {
        assertFalse(
            shouldRestoreLocalArCoreFingerprint(
                isArCoreAvailable = true,
                coopRole = CoopRole.GUEST,
                peerSpatialFramePresent = true,
            )
        )
    }

    @Test
    fun `local fingerprint restore remains enabled outside peer-owned guest session`() {
        assertTrue(
            shouldRestoreLocalArCoreFingerprint(
                isArCoreAvailable = true,
                coopRole = CoopRole.NONE,
                peerSpatialFramePresent = false,
            )
        )
        assertTrue(
            shouldRestoreLocalArCoreFingerprint(
                isArCoreAvailable = true,
                coopRole = CoopRole.HOST,
                peerSpatialFramePresent = false,
            )
        )
        assertFalse(
            shouldRestoreLocalArCoreFingerprint(
                isArCoreAvailable = false,
                coopRole = CoopRole.NONE,
                peerSpatialFramePresent = false,
            )
        )
    }
}
