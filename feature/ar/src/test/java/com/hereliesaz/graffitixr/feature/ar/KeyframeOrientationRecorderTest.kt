package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeOrientationRecorderTest {

    @Test
    fun `snapshot is null before anything is recorded`() {
        assertNull(KeyframeOrientationRecorder().snapshot())
    }

    @Test
    fun `records samples in order and flattens quaternions`() {
        val r = KeyframeOrientationRecorder()
        r.record(10L, floatArrayOf(0f, 0f, 0f, 1f))
        r.record(20L, floatArrayOf(0f, 1f, 0f, 0f))

        val snap = r.snapshot()!!
        assertEquals(2, snap.count)
        assertTrue(longArrayOf(10L, 20L).contentEquals(snap.timestampsNs))
        assertTrue(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f).contentEquals(snap.quaternions),
        )
    }

    @Test
    fun `ignores a malformed quaternion without throwing`() {
        val r = KeyframeOrientationRecorder()
        r.record(1L, floatArrayOf(0f, 0f, 0f)) // too short
        r.record(2L, floatArrayOf(0f, 0f, 0f, 1f, 0f)) // too long
        assertEquals(0, r.size())
        assertNull(r.snapshot())
    }

    @Test
    fun `copies the input array so later mutation does not corrupt the record`() {
        val r = KeyframeOrientationRecorder()
        val buf = floatArrayOf(0f, 0f, 0f, 1f)
        r.record(1L, buf)
        buf[3] = 99f // caller reuses the buffer
        assertEquals(1f, r.snapshot()!!.quaternions[3], 0f)
    }

    @Test
    fun `drops oldest beyond the cap`() {
        val r = KeyframeOrientationRecorder(maxSamples = 2)
        r.record(1L, floatArrayOf(0f, 0f, 0f, 1f))
        r.record(2L, floatArrayOf(0f, 0f, 0f, 1f))
        r.record(3L, floatArrayOf(0f, 0f, 0f, 1f))
        val snap = r.snapshot()!!
        assertEquals(2, snap.count)
        assertTrue("oldest dropped", longArrayOf(2L, 3L).contentEquals(snap.timestampsNs))
    }

    @Test
    fun `clear empties the recorder`() {
        val r = KeyframeOrientationRecorder()
        r.record(1L, floatArrayOf(0f, 0f, 0f, 1f))
        r.clear()
        assertEquals(0, r.size())
        assertNull(r.snapshot())
    }
}
