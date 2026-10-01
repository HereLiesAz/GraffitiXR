package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneArPlacementGenerationTest {

    @Test
    fun staleStandalonePlacement_resetsOnlySpatialFields() {
        val uri = mockk<android.net.Uri>()
        every { uri.toString() } returns "file://wall"
        val project = GraffitiProject(
            id = "p",
            sphereSlamReferenceUri = uri,
            sphereSlamAnchorGeneration = 8L,
            sphereSlamPlacementAnchorGeneration = 7L,
            sphereSlamModeAdjustment = ModeAdjustment(
                    offsetX = 2f,
                    offsetY = -1f,
                    scale = 3f,
                    rotation = 22f,
                    rotationX = 10f,
                    rotationY = -5f,
                    brightness = 0.2f,
                    contrast = 1.3f,
                    saturation = 0.7f,
                    opacity = 0.6f,
                    isInverted = true,
                    isTransformLocked = true,
                ),
        )

        val restored = requireNotNull(standaloneArAdjustmentForProject(project))

        assertEquals(0f, restored.offsetX, 0f)
        assertEquals(0f, restored.offsetY, 0f)
        assertEquals(1f, restored.scale, 0f)
        assertEquals(0f, restored.rotation, 0f)
        assertEquals(0f, restored.rotationX, 0f)
        assertEquals(0f, restored.rotationY, 0f)
        assertFalse(restored.isTransformLocked)
        assertEquals(0.2f, restored.brightness, 0f)
        assertEquals(1.3f, restored.contrast, 0f)
        assertEquals(0.7f, restored.saturation, 0f)
        assertEquals(0.6f, restored.opacity, 0f)
        assertEquals(true, restored.isInverted)
    }

    @Test
    fun matchingStandaloneGeneration_restoresPlacementExactly() {
        val uri = mockk<android.net.Uri>()
        every { uri.toString() } returns "file://wall"
        val adjustment = ModeAdjustment(offsetX = 0.4f, scale = 1.5f, rotation = 14f)
        val project = GraffitiProject(
            id = "p",
            sphereSlamReferenceUri = uri,
            sphereSlamAnchorGeneration = 4L,
            sphereSlamPlacementAnchorGeneration = 4L,
            sphereSlamModeAdjustment = adjustment,
        )

        assertEquals(adjustment, standaloneArAdjustmentForProject(project))
    }

    @Test
    fun noSavedArAdjustment_returnsNull() {
        val project = GraffitiProject(id = "p")
        assertNull(standaloneArAdjustmentForProject(project))
    }
}
