package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseFusionHybridObservationTest {
    @Test
    fun `hybrid correction is stored anchor locally and survives ARCore world rebase`() {
        val fusion = PoseFusion()
        val solveBackbone = translated(0f, 0f, -2.3f)
        val physicalAnchor = translated(0f, 0f, -2.0f)

        val first = fusion.currentAnchorFromHybridObservation(
            currentBackbone = solveBackbone,
            backboneAtObservation = solveBackbone,
            correctedAnchorAtObservation = physicalAnchor,
            observationTimestampNs = 100L,
            confidence = 0.95f,
            inliers = 30,
        )
        assertEquals(-2.0f, first[14], 1e-4f)

        // Simulate an ARCore global world rewrite G = +5m X. Both the live backbone and the physical
        // anchor move by G numerically; the standing local correction must remain +0.3m Z.
        val rebasedBackbone = translated(5f, 0f, -2.3f)
        val held = fusion.currentAnchorFromHybridObservation(
            currentBackbone = rebasedBackbone,
            backboneAtObservation = solveBackbone,
            correctedAnchorAtObservation = physicalAnchor,
            observationTimestampNs = 100L, // same observation -> HOLD
            confidence = 0.95f,
            inliers = 30,
        )
        assertEquals(5f, held[12], 1e-4f)
        assertEquals(-2f, held[14], 1e-4f)
    }

    @Test
    fun `weak hybrid observation cannot create a correction`() {
        val fusion = PoseFusion()
        val backbone = translated(0f,0f,-2.3f)
        val result = fusion.currentAnchorFromHybridObservation(
            currentBackbone = backbone,
            backboneAtObservation = backbone,
            correctedAnchorAtObservation = translated(0f,0f,-2f),
            observationTimestampNs = 10L,
            confidence = 0.2f,
            inliers = 30,
        )
        assertEquals(-2.3f, result[14], 1e-4f)
    }

    private fun translated(x: Float, y: Float, z: Float) = floatArrayOf(
        1f,0f,0f,0f,
        0f,1f,0f,0f,
        0f,0f,1f,0f,
        x,y,z,1f,
    )
}
