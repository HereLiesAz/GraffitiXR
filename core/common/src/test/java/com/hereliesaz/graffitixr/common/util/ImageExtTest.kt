package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ImageExtTest {

    @Test
    fun pixel5StandaloneStillIsReducedBelowTargetReviewBudget() {
        val sourceWidth = 3024
        val sourceHeight = 4032

        val (width, height) = fitWithinPixelBudget(sourceWidth, sourceHeight)

        assertTrue(width.toLong() * height.toLong() <= TARGET_REVIEW_MAX_PIXELS)
        assertTrue(width < sourceWidth)
        assertTrue(height < sourceHeight)

        val sourceAspect = sourceWidth.toDouble() / sourceHeight.toDouble()
        val scaledAspect = width.toDouble() / height.toDouble()
        assertTrue(abs(sourceAspect - scaledAspect) < 0.001)
    }

    @Test
    fun existingArCoreSizedCaptureIsNotRescaled() {
        assertEquals(
            1600 to 1200,
            fitWithinPixelBudget(1600, 1200),
        )
    }
}
