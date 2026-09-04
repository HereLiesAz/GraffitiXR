package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [HomographyArTracker.HomographyPose]'s pure-Kotlin logic only. Anything that reaches
 * [HomographyTrackerNative] requires the real `.so` and belongs in an instrumented test, not here
 * — touching that singleton triggers its `init { NativeLibLoader.loadAll() }`.
 */
class HomographyArTrackerTest {

    private fun pose(matrix: FloatArray = FloatArray(16) { it.toFloat() }, confidence: Float = 0.8f) =
        HomographyArTracker.HomographyPose(matrix, confidence)

    @Test
    fun `rejects a viewMatrix that is not length 16`() {
        assertThrows(IllegalArgumentException::class.java) {
            HomographyArTracker.HomographyPose(FloatArray(15), 0.5f)
        }
    }

    @Test
    fun `equals and hashCode compare viewMatrix by content, not reference`() {
        val a = pose(FloatArray(16) { it.toFloat() })
        val b = pose(FloatArray(16) { it.toFloat() })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `differing confidence breaks equality even with identical matrices`() {
        val a = pose(confidence = 0.8f)
        val b = pose(confidence = 0.9f)
        assertNotEquals(a, b)
    }

    @Test
    fun `differing viewMatrix content breaks equality`() {
        val a = pose(FloatArray(16) { it.toFloat() })
        val b = pose(FloatArray(16) { (it + 1).toFloat() })
        assertNotEquals(a, b)
    }

    @Test
    fun `a pose is not equal to an unrelated type`() {
        assertFalse(pose().equals("not a pose"))
    }

    @Test
    fun `a pose is equal to itself by reference`() {
        val p = pose()
        assertTrue(p == p)
    }
}
