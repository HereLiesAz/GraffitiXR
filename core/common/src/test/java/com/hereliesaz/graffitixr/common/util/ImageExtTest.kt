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

    @Test
    fun aMostlyBlackBoardWithChalkMarksLooksForLightMarks() {
        // 90% near-black board (luma 20), 10% chalk strokes (luma 220).
        val luma = IntArray(1000) { if (it % 10 == 0) 220 else 20 }
        assertTrue(marksAreLight(luma))
    }

    @Test
    fun aLightWallWithDarkMarksLooksForDarkMarks() {
        val luma = IntArray(1000) { if (it % 10 == 0) 30 else 200 }
        assertTrue(!marksAreLight(luma))
    }

    @Test
    fun aLightWallKeepsItsDarkMarksAndGainsWhitePaintButNotHalos() {
        // White wall (median 200), offset 25.
        assertTrue(isMarkPixel(v = 60, avg = 190, median = 200, offset = 25, lightMarks = false)) // dark mark
        assertTrue(isMarkPixel(v = 250, avg = 200, median = 200, offset = 25, lightMarks = false)) // white paint
        // Next to a big dark mark the local average drops to 150: the bare wall (200) beats it, not the median.
        assertTrue(!isMarkPixel(v = 200, avg = 150, median = 200, offset = 25, lightMarks = false))
    }

    @Test
    fun aBlackBoardKeepsItsLightMarksAndGainsDeepBlackPaint() {
        // Black board (median 40), offset 25.
        assertTrue(isMarkPixel(v = 200, avg = 50, median = 40, offset = 25, lightMarks = true)) // chalk
        assertTrue(isMarkPixel(v = 5, avg = 45, median = 40, offset = 25, lightMarks = true)) // deep black paint
        // Next to a big chalk mark the local average rises to 90: the bare board (40) is below it, not the median.
        assertTrue(!isMarkPixel(v = 40, avg = 90, median = 40, offset = 25, lightMarks = true))
    }
}
