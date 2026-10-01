package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneAtlasGrowthTest {

    private val intrinsics = CameraIntrinsics(
        fx = 800f,
        fy = 800f,
        cx = 500f,
        cy = 300f,
        width = 1000,
        height = 600,
    )

    private fun viewForCameraAt(x: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        -x, 0f, -1f, 1f,
    )

    @Test
    fun movedCamera_proposesOverlappingPageInCanonicalCoordinates() {
        val proposal = StandaloneAtlasGrowth.propose(
            cameraFromCanonicalOpenGl = viewForCameraAt(0.55f),
            intrinsics = intrinsics,
            frameWidth = 1000,
            frameHeight = 600,
            rootWidthUnits = 1f,
            rootHeightUnits = 0.5f,
            existingPages = listOf(
                StandaloneAtlasPageWindow(0, 0f, 0f, 1f, 0.5f),
            ),
        )

        assertNotNull(proposal)
        proposal!!
        assertEquals(0.55f, proposal.centerX, 0.0001f)
        assertEquals(0f, proposal.centerY, 0.0001f)
        assertEquals(1f, proposal.width, 0.0001f)
        assertEquals(0.5f, proposal.height, 0.0001f)
        assertEquals(4, proposal.sourceCorners.size)
    }

    @Test
    fun smallBaseline_doesNotGrowAtlas() {
        val proposal = StandaloneAtlasGrowth.propose(
            cameraFromCanonicalOpenGl = viewForCameraAt(0.2f),
            intrinsics = intrinsics,
            frameWidth = 1000,
            frameHeight = 600,
            rootWidthUnits = 1f,
            rootHeightUnits = 0.5f,
            existingPages = listOf(
                StandaloneAtlasPageWindow(0, 0f, 0f, 1f, 0.5f),
            ),
        )

        assertNull(proposal)
    }

    @Test
    fun canonicalPageTransform_keepsRootFrameAndMovesOnlyPageOrigin() {
        val transform = StandaloneAtlasGrowth.canonicalFromPage(1.25f, -0.4f)

        assertEquals(1f, transform[0], 0f)
        assertEquals(1f, transform[5], 0f)
        assertEquals(1f, transform[10], 0f)
        assertEquals(1.25f, transform[12], 0f)
        assertEquals(-0.4f, transform[13], 0f)
        assertEquals(0f, transform[14], 0f)
        assertEquals(1f, transform[15], 0f)
    }
}
