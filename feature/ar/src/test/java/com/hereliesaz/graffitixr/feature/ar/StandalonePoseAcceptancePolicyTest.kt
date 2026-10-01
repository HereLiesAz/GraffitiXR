package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandalonePoseAcceptancePolicyTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun acceptsPinnedArtoolkitxBoundaryValues() {
        val result = StandalonePoseAcceptancePolicy().evaluate(
            identity,
            inlierCount = 4,
            reprojectionError = 10f,
        )
        assertTrue(result.accepted)
    }

    @Test
    fun rejectsTooFewInliers() {
        val result = StandalonePoseAcceptancePolicy().evaluate(identity, 3, 1f)
        assertFalse(result.accepted)
        assertEquals(StandalonePoseRejection.TOO_FEW_INLIERS, result.rejection)
    }

    @Test
    fun rejectsExcessiveError() {
        val result = StandalonePoseAcceptancePolicy().evaluate(identity, 12, 10.01f)
        assertFalse(result.accepted)
        assertEquals(StandalonePoseRejection.EXCESSIVE_REPROJECTION_ERROR, result.rejection)
    }

    @Test
    fun rejectsNonFinitePoseOrError() {
        val badPose = identity.copyOf().also { it[12] = Float.NaN }
        assertEquals(
            StandalonePoseRejection.NON_FINITE,
            StandalonePoseAcceptancePolicy().evaluate(badPose, 12, 1f).rejection,
        )
        assertEquals(
            StandalonePoseRejection.NON_FINITE,
            StandalonePoseAcceptancePolicy().evaluate(identity, 12, Float.NaN).rejection,
        )
    }

    @Test
    fun thresholdsAreConfigurableButCannotUndercutKpmMinimumGeometry() {
        val policy = StandalonePoseAcceptancePolicy(
            StandalonePoseAcceptanceConfig(minInliers = 8, maxReprojectionError = 3f),
        )
        assertEquals(
            StandalonePoseRejection.TOO_FEW_INLIERS,
            policy.evaluate(identity, 7, 1f).rejection,
        )
        assertEquals(
            StandalonePoseRejection.EXCESSIVE_REPROJECTION_ERROR,
            policy.evaluate(identity, 9, 3.1f).rejection,
        )
    }
}
