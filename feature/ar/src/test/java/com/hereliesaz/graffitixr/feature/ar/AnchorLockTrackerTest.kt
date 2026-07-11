package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorLockTrackerTest {

    @Test
    fun `does not lock before the window fills`() {
        val t = AnchorLockTracker(windowSize = 30, jitterThresholdMeters = 0.01f)
        repeat(29) { assertFalse(t.update(1f, 2f, 3f)) }
        assertFalse(t.locked)
        assertEquals(0f, t.stability, 0f)
    }

    @Test
    fun `locks when a full window holds one spot`() {
        val t = AnchorLockTracker(windowSize = 30, jitterThresholdMeters = 0.01f)
        var locked = false
        repeat(30) { locked = t.update(1f, 2f, 3f) }
        assertTrue(locked)
        assertTrue(t.locked)
        assertEquals(0f, t.jitterMeters, 1e-6f)
        assertEquals(1f, t.stability, 1e-6f)
    }

    @Test
    fun `does not lock while jitter exceeds threshold`() {
        val t = AnchorLockTracker(windowSize = 10, jitterThresholdMeters = 0.01f)
        // Alternate positions 5cm apart — well beyond the 1cm threshold.
        repeat(40) { i ->
            val x = if (i % 2 == 0) 0f else 0.05f
            t.update(x, 0f, 0f)
        }
        assertFalse(t.locked)
        assertTrue(t.jitterMeters > 0.01f)
    }

    @Test
    fun `small jitter within threshold still locks`() {
        val t = AnchorLockTracker(windowSize = 10, jitterThresholdMeters = 0.01f)
        // +/- 2mm around a point → max radius ~2mm < 1cm.
        repeat(10) { i ->
            val x = if (i % 2 == 0) 0f else 0.002f
            t.update(x, 0f, 0f)
        }
        assertTrue(t.locked)
    }

    @Test
    fun `lock latches through a later jitter spike`() {
        val t = AnchorLockTracker(windowSize = 10, jitterThresholdMeters = 0.01f)
        repeat(10) { t.update(0f, 0f, 0f) }
        assertTrue(t.locked)
        // A big move afterwards must NOT un-lock (swap is mid-flight).
        repeat(10) { t.update(1f, 1f, 1f) }
        assertTrue(t.locked)
    }

    @Test
    fun `reset clears lock and window`() {
        val t = AnchorLockTracker(windowSize = 5, jitterThresholdMeters = 0.01f)
        repeat(5) { t.update(0f, 0f, 0f) }
        assertTrue(t.locked)
        t.reset()
        assertFalse(t.locked)
        assertEquals(0f, t.stability, 0f)
        // After reset it needs a fresh full window before locking again.
        repeat(4) { t.update(0f, 0f, 0f) }
        assertFalse(t.locked)
        assertTrue(t.update(0f, 0f, 0f))
    }
}
