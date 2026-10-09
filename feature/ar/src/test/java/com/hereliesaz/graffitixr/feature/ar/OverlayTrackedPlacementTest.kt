package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayTrackedPlacementTest {

    // 200x100 design on a 1000x1000 view (screen fit 5x) and a 2x1 target (half 1 x 0.5).
    private fun place(
        layerScale: Float = 1f,
        layerRotationZDeg: Float = 0f,
        layerOffsetXPx: Float = 0f,
        layerOffsetYPx: Float = 0f,
        modeOffsetXPx: Float = 0f,
        modeOffsetYPx: Float = 0f,
        modeScale: Float = 1f,
        modeRotationDeg: Float = 0f,
    ) = requireNotNull(
        overlayTrackedPlacement(
            targetHalfWidth = 1f,
            targetHalfHeight = 0.5f,
            designWidthPx = 200,
            designHeightPx = 100,
            viewWidthPx = 1000,
            viewHeightPx = 1000,
            layerScale = layerScale,
            layerRotationZDeg = layerRotationZDeg,
            layerOffsetXPx = layerOffsetXPx,
            layerOffsetYPx = layerOffsetYPx,
            modeOffsetXPx = modeOffsetXPx,
            modeOffsetYPx = modeOffsetYPx,
            modeScale = modeScale,
            modeRotationDeg = modeRotationDeg,
        ),
    )

    @Test
    fun identityFillsTheFittedTarget() {
        val p = place()
        assertEquals(1f, p.halfWidth, 1e-4f)
        assertEquals(0.5f, p.halfHeight, 1e-4f)
        assertEquals(0f, p.panX, 1e-4f)
        assertEquals(0f, p.panY, 1e-4f)
        assertEquals(1f, p.scale, 1e-4f)
    }

    @Test
    fun modeOffsetIsRelativeToTheFittedDesignAndYFlips() {
        // The design is 1000px wide on screen and 2 units on the target: 0.002 units/px.
        val p = place(modeOffsetXPx = 500f, modeOffsetYPx = 250f)
        assertEquals(1f, p.panX, 1e-4f)
        assertEquals(-0.5f, p.panY, 1e-4f)
    }

    @Test
    fun modeRotationAndScalePassThroughWithGlSign() {
        val p = place(modeScale = 2f, modeRotationDeg = 30f)
        assertEquals(2f, p.scale, 1e-4f)
        assertEquals(-30f, p.rotationZDeg, 1e-4f)
    }

    @Test
    fun layerScaleAndRotationSizeTheCompositeBounds() {
        val p = place(layerScale = 2f, layerRotationZDeg = 90f)
        // Rotated 90 degrees: bounds swap to 100x200 bitmap px, doubled, at 0.01 units/bitmap px.
        assertEquals(1f, p.halfWidth, 1e-3f)
        assertEquals(2f, p.halfHeight, 1e-3f)
    }

    @Test
    fun layerOffsetIsRotatedAndScaledByTheModeTransform() {
        // Layer +100px right, mode 90 CW and 2x: screen +200px down -> -0.4 units y-up.
        val p = place(layerOffsetXPx = 100f, modeScale = 2f, modeRotationDeg = 90f)
        assertEquals(0f, p.panX, 1e-4f)
        assertEquals(-0.4f, p.panY, 1e-4f)
    }

    @Test
    fun unmeasuredViewHasNoPlacement() {
        assertNull(overlayTrackedPlacement(1f, 0.5f, 200, 100, 0, 0, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    }
}
