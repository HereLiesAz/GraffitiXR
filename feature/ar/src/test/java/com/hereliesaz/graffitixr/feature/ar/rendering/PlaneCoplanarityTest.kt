package com.hereliesaz.graffitixr.feature.ar.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry behind the co-planar plane merge that stops one wall from rendering as a stack of
 * overlapping ARCore detections, and the timing of the non-green surface dissolve.
 */
class PlaneCoplanarityTest {

    private val wallNormal = floatArrayOf(0f, 0f, 1f)   // wall facing +Z
    private val floorNormal = floatArrayOf(0f, 1f, 0f)  // floor facing +Y

    @Test
    fun `two patches across the same wall merge`() {
        // Far apart horizontally and vertically, but on the same plane: still one surface.
        assertTrue(
            PlaneRenderer.isCoplanar(
                wallNormal, 0f, 0f, 0f,
                wallNormal, 3f, 2f, 0f,
            )
        )
    }

    @Test
    fun `patch offset along the normal past the tolerance stays separate`() {
        // 0.5 m in front of the wall — a different surface, e.g. a pillar face.
        assertFalse(
            PlaneRenderer.isCoplanar(
                wallNormal, 0f, 0f, 0f,
                wallNormal, 0f, 0f, 0.5f,
            )
        )
    }

    @Test
    fun `depth jitter within the tolerance still merges`() {
        assertTrue(
            PlaneRenderer.isCoplanar(
                wallNormal, 0f, 0f, 0f,
                wallNormal, 1f, 1f, 0.05f,
            )
        )
    }

    @Test
    fun `perpendicular surfaces never merge`() {
        assertFalse(
            PlaneRenderer.isCoplanar(
                wallNormal, 0f, 0f, 0f,
                floorNormal, 0f, 0f, 0f,
            )
        )
    }

    @Test
    fun `anti-parallel normals on the same plane merge`() {
        // ARCore can report one wall's normal flipped depending on the approach direction.
        assertTrue(
            PlaneRenderer.isCoplanar(
                wallNormal, 0f, 0f, 0f,
                floatArrayOf(0f, 0f, -1f), 1f, 1f, 0f,
            )
        )
    }

    @Test
    fun `small normal jitter within the angular tolerance merges`() {
        // ~8 degrees off — inside the 0.97 dot tolerance.
        val jittered = floatArrayOf(0f, 0.139f, 0.990f)
        assertTrue(
            PlaneRenderer.isCoplanar(
                wallNormal, 0f, 0f, 0f,
                jittered, 0.5f, 0f, 0f,
            )
        )
    }

    @Test
    fun `surface is fully drawn through the five second hold`() {
        assertEquals(1f, PlaneRenderer.dissolveFade(0L), 1e-4f)
        assertEquals(1f, PlaneRenderer.dissolveFade(4_999L), 1e-4f)
        assertEquals(1f, PlaneRenderer.dissolveFade(PlaneRenderer.HOLD_MS), 1e-4f)
    }

    @Test
    fun `dissolve ramps linearly over ten seconds after the hold`() {
        assertEquals(1f, PlaneRenderer.dissolveFade(5_001L), 1e-3f)
        assertEquals(0.75f, PlaneRenderer.dissolveFade(7_500L), 1e-4f)
        assertEquals(0.5f, PlaneRenderer.dissolveFade(10_000L), 1e-4f)
        assertEquals(0.25f, PlaneRenderer.dissolveFade(12_500L), 1e-4f)
    }

    @Test
    fun `surface is gone at fifteen seconds and stays gone`() {
        assertEquals(0f, PlaneRenderer.dissolveFade(PlaneRenderer.HOLD_MS + PlaneRenderer.DISSOLVE_MS), 1e-4f)
        assertEquals(0f, PlaneRenderer.dissolveFade(60_000L), 1e-4f)
    }

    @Test
    fun `distinct walls of a corridor stay separate`() {
        // Parallel, facing each other, 2 m apart.
        assertFalse(
            PlaneRenderer.isCoplanar(
                wallNormal, 0f, 0f, 0f,
                floatArrayOf(0f, 0f, -1f), 0f, 0f, 2f,
            )
        )
    }
}
