package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.feature.ar.anchor.CorrectionSourceArbiter.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionSourceArbiterTest {
    private val ms = 1_000_000L
    private val window = CorrectionSourceArbiter.HYBRID_FRESH_WINDOW_NS

    @Test fun `reloc drives until hybrid has ever accepted`() {
        val a = CorrectionSourceArbiter()
        assertEquals(Source.RELOC, a.select(0, hybridAccepted = false, hybridFrameReady = true, hasRelocCapturePose = true))
        assertEquals(Source.RELOC, a.select(100 * ms, false, true, true))
    }

    @Test fun `hybrid holds the anchor on frames without an observation instead of falling to reloc`() {
        val a = CorrectionSourceArbiter()
        assertEquals(Source.HYBRID, a.select(0, true, true, true))
        // Alternating accepted / not-accepted frames must never produce RELOC inside the window.
        for (i in 1..50) {
            val accepted = i % 7 == 0
            val src = a.select(i * 33 * ms, accepted, true, true)
            assertEquals(if (accepted) Source.HYBRID else Source.HOLD, src)
        }
    }

    @Test fun `reloc takes over only after hybrid was silent for the whole window`() {
        val a = CorrectionSourceArbiter()
        a.select(0, true, true, true)
        assertEquals(Source.HOLD, a.select(window, false, true, true))
        assertEquals(Source.RELOC, a.select(window + 1, false, true, true))
    }

    @Test fun `a fresh hybrid acceptance after the handover takes the anchor back`() {
        val a = CorrectionSourceArbiter()
        a.select(0, true, true, true)
        assertEquals(Source.RELOC, a.select(window + 10 * ms, false, true, true))
        assertEquals(Source.HYBRID, a.select(window + 20 * ms, true, true, true))
        assertEquals(Source.HOLD, a.select(window + 30 * ms, false, true, true))
    }

    @Test fun `reset forgets hybrid history`() {
        val a = CorrectionSourceArbiter()
        a.select(0, true, true, true)
        a.reset()
        assertEquals(Source.RELOC, a.select(10 * ms, false, true, true))
    }

    @Test fun `without a capture pose a ready hybrid page holds, otherwise the backbone is used`() {
        val a = CorrectionSourceArbiter()
        assertEquals(Source.HOLD, a.select(0, false, hybridFrameReady = true, hasRelocCapturePose = false))
        assertEquals(Source.NONE, a.select(1, false, hybridFrameReady = false, hasRelocCapturePose = false))
    }

    @Test fun `losing the hybrid page hands straight back to reloc`() {
        val a = CorrectionSourceArbiter()
        a.select(0, true, true, true)
        assertEquals(Source.RELOC, a.select(10 * ms, false, hybridFrameReady = false, hasRelocCapturePose = true))
    }
}
