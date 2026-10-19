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

    @Test fun `losing the hybrid page holds through the window, then hands to reloc`() {
        val a = CorrectionSourceArbiter()
        a.select(0, true, true, true)
        // KPM page unloaded one frame later: hybrid was fresh, so the correction is HELD, not handed
        // to the other estimator on the next frame.
        assertEquals(Source.HOLD, a.select(10 * ms, false, hybridFrameReady = false, hasRelocCapturePose = true))
        assertEquals(Source.HOLD, a.select(window, false, hybridFrameReady = false, hasRelocCapturePose = true))
        assertEquals(Source.RELOC, a.select(window + 1, false, hybridFrameReady = false, hasRelocCapturePose = true))
    }

    @Test fun `a never-fresh unloaded page does not hold`() {
        val a = CorrectionSourceArbiter()
        assertEquals(Source.RELOC, a.select(0, false, hybridFrameReady = false, hasRelocCapturePose = true))
        assertEquals(Source.NONE, a.select(1, false, hybridFrameReady = false, hasRelocCapturePose = false))
    }

    @Test fun `a healthy fresh hybrid keeps a paint-backed lock out`() {
        val a = CorrectionSourceArbiter()
        assertEquals(Source.HYBRID, a.select(0, true, true, true, hybridConfidence = 0.9f))
        assertEquals(Source.HOLD, a.select(10 * ms, false, true, true, relocPaintBacked = true))
    }

    @Test fun `a paint-backed lock drives once hybrid degrades, and keeps the anchor`() {
        val a = CorrectionSourceArbiter()
        assertEquals(Source.HYBRID, a.select(0, true, true, true, hybridConfidence = 0.3f))
        assertEquals(Source.RELOC, a.select(10 * ms, false, true, true, relocPaintBacked = true))
        // A healthy hybrid acceptance does not take it back while paint keeps locking...
        assertEquals(Source.RELOC, a.select(20 * ms, true, true, true, relocPaintBacked = true, hybridConfidence = 0.9f))
        // ...and frames without a paint lock hold, through the window.
        assertEquals(Source.HOLD, a.select(30 * ms, false, true, true))
        assertEquals(Source.HOLD, a.select(20 * ms + window, false, true, true))
        // Paint silent past the window: back to the old rule.
        assertEquals(Source.HYBRID, a.select(30 * ms + window, true, true, true, hybridConfidence = 0.9f))
    }

    @Test fun `a paint-backed lock drives when hybrid is silent past the window`() {
        val a = CorrectionSourceArbiter()
        a.select(0, true, true, true, hybridConfidence = 0.9f)
        assertEquals(Source.RELOC, a.select(window + 1, false, true, true, relocPaintBacked = true))
    }

    @Test fun `a gated paint-backed lock is never selected`() {
        val a = CorrectionSourceArbiter()
        a.select(0, true, true, true, hybridConfidence = 0.1f)
        assertEquals(Source.HOLD, a.select(10 * ms, false, true, true, relocGated = true, relocPaintBacked = true))
    }

    @Test fun `a gated reloc path is never selected`() {
        val a = CorrectionSourceArbiter()
        // Nothing but reloc available: gated means the raw backbone, not RELOC.
        assertEquals(Source.NONE, a.select(0, false, false, hasRelocCapturePose = true, relocGated = true))
        // A ready hybrid page holds instead of falling to reloc.
        assertEquals(Source.HOLD, a.select(1, false, true, hasRelocCapturePose = true, relocGated = true))
        // Even once hybrid has been silent past the window.
        a.select(2, true, true, true, relocGated = true)
        assertEquals(Source.HOLD, a.select(window + 10 * ms, false, true, true, relocGated = true))
        // Hybrid observations still drive while gated.
        assertEquals(Source.HYBRID, a.select(window + 20 * ms, true, true, true, relocGated = true))
        // Lifting the gate returns the normal rule.
        a.reset()
        assertEquals(Source.RELOC, a.select(0, false, true, true, relocGated = false))
    }
}
