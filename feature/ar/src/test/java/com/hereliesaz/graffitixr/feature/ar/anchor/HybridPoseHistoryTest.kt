package com.hereliesaz.graffitixr.feature.ar.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class HybridPoseHistoryTest {
    @Test
    fun `nearest pairs KPM to the bounded ARCore sensor timestamp`() {
        val h = HybridPoseHistory(capacity = 4)
        h.add(100L, translated(1f), translated(10f))
        h.add(200L, translated(2f), translated(20f))
        h.add(300L, translated(3f), translated(30f))
        val sample = h.nearest(212L, maxDeltaNs = 20L)
        assertNotNull(sample)
        assertEquals(200L, sample!!.timestampNs)
        assertEquals(2f, sample.viewMatrix[12], 0f)
        assertEquals(20f, sample.backboneMatrix[12], 0f)
    }

    @Test
    fun `pair outside timestamp budget is rejected`() {
        val h = HybridPoseHistory()
        h.add(100L, translated(1f), translated(1f))
        assertNull(h.nearest(151L, maxDeltaNs = 50L))
    }

    @Test
    fun `clock discontinuity clears the old epoch`() {
        val h = HybridPoseHistory()
        h.add(1_000L, translated(1f), translated(1f))
        h.add(900L, translated(2f), translated(2f))
        assertNull(h.nearest(1_000L, maxDeltaNs = 0L))
        assertEquals(900L, h.nearest(900L, 0L)!!.timestampNs)
    }

    private fun translated(x: Float) = floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,0f,0f,1f,
    )
}
