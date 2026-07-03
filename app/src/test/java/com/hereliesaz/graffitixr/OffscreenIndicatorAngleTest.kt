package com.hereliesaz.graffitixr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guards for the two off-screen indicator arrow angles. Both feed `Modifier.rotate`,
 * which is compass-convention (0° = up, CW positive), and the icon (`ArrowUpward`) points up at
 * 0°. Previously both formulas produced a math-convention angle (from the +x axis, CCW positive)
 * — so an active layer to the right of centre displayed an up-arrow, and a wall target directly
 * above the camera showed a left-arrow. This test locks the correct compass angles for the four
 * cardinal directions on both.
 */
class OffscreenIndicatorAngleTest {

    private val eps = 1e-4f

    // --- Screen-space (y-down): active-layer indicator ---

    @Test
    fun `screen-space target directly above yields 0 degrees (up)`() {
        assertEquals(0f, screenSpaceArrowAngleDeg(0f, -100f), eps)
    }

    @Test
    fun `screen-space target to the right yields 90 degrees`() {
        assertEquals(90f, screenSpaceArrowAngleDeg(100f, 0f), eps)
    }

    @Test
    fun `screen-space target directly below yields 180 degrees`() {
        assertEquals(180f, screenSpaceArrowAngleDeg(0f, 100f), eps)
    }

    @Test
    fun `screen-space target to the left yields negative 90 degrees`() {
        assertEquals(-90f, screenSpaceArrowAngleDeg(-100f, 0f), eps)
    }

    @Test
    fun `screen-space target above-right yields 45 degrees`() {
        assertEquals(45f, screenSpaceArrowAngleDeg(100f, -100f), eps)
    }

    // --- View-space (y-up): wall-target indicator ---

    @Test
    fun `view-space target directly above yields 0 degrees (up)`() {
        assertEquals(0f, viewSpaceArrowAngleDeg(0f, 1f), eps)
    }

    @Test
    fun `view-space target to camera-right yields 90 degrees`() {
        assertEquals(90f, viewSpaceArrowAngleDeg(1f, 0f), eps)
    }

    @Test
    fun `view-space target directly below yields 180 degrees`() {
        assertEquals(180f, viewSpaceArrowAngleDeg(0f, -1f), eps)
    }

    @Test
    fun `view-space target to camera-left yields negative 90 degrees`() {
        assertEquals(-90f, viewSpaceArrowAngleDeg(-1f, 0f), eps)
    }

    @Test
    fun `view-space upper-right diagonal yields 45 degrees`() {
        assertEquals(45f, viewSpaceArrowAngleDeg(1f, 1f), eps)
    }
}
