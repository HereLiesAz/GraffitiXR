package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStressPolicyTest {

    @Test
    fun `stress combines the safety outputs`() {
        assertEquals(DeviceStress.NONE, deviceStress(perceptionSystemThrottle = false, batteryTier = 0))
        assertEquals(DeviceStress.MODERATE, deviceStress(perceptionSystemThrottle = false, batteryTier = 1))
        assertEquals(DeviceStress.HIGH, deviceStress(perceptionSystemThrottle = false, batteryTier = 2))
        assertEquals(DeviceStress.HIGH, deviceStress(perceptionSystemThrottle = true, batteryTier = 0))
    }

    @Test
    fun `no stress keeps the native back-off defaults`() {
        assertEquals(200 to 60, relocBackoffMs(DeviceStress.NONE))
        assertTrue(relocBackoffMs(DeviceStress.HIGH).first > relocBackoffMs(DeviceStress.MODERATE).first)
    }

    /**
     * CameraX keep-only-latest over a fixed-rate camera: when the analyzer frees up it gets the newest
     * frame that arrived while it was busy, else the next one. A refused frame costs no time.
     * Returns the admitted frame rate.
     */
    private fun simulate(gate: AnalysisFrameGate, frameNs: Long, workNs: Long, seconds: Int = 20): Double {
        var free = 0L
        var lastFrameIndex = -1L
        var admitted = 0
        val end = seconds * 1_000_000_000L
        while (free < end) {
            val latest = free / frameNs
            val index = if (latest > lastFrameIndex) latest else lastFrameIndex + 1
            val ts = index * frameNs
            lastFrameIndex = index
            val start = maxOf(free, ts)
            if (gate.admit(ts)) {
                admitted++
                gate.onWorkDone(workNs)
                free = start + workNs
            } else {
                free = start
            }
        }
        return admitted / seconds.toDouble()
    }

    private val frame30 = 33_333_333L

    @Test
    fun `nothing is dropped while work fits in a frame`() {
        val gate = AnalysisFrameGate().apply { adaptiveCadence = true }
        assertEquals(30.0, simulate(gate, frame30, workNs = 20_000_000L), 0.2)
    }

    @Test
    fun `adaptive off leaves an overrunning analyzer flat out`() {
        val gate = AnalysisFrameGate()
        // 45 ms of work over 33 ms frames: keep-only-latest restarts at once, 1 / 45 ms ≈ 22 fps.
        assertEquals(1e9 / 45e6, simulate(gate, frame30, workNs = 45_000_000L), 0.5)
    }

    @Test
    fun `adaptive on keeps duty within budget once work exceeds a frame`() {
        val gate = AnalysisFrameGate().apply { adaptiveCadence = true }
        val workNs = 45_000_000L
        val fps = simulate(gate, frame30, workNs)
        val duty = fps * workNs / 1e9
        assertTrue("duty $duty fps $fps", duty <= AnalysisFrameGate.DUTY_BUDGET + 0.05)
        assertTrue("fps $fps", fps >= 7.0)
    }

    @Test
    fun `adaptive cadence triggers when work spans several frames`() {
        // 75 ms of work over 33 ms frames: the analyzer only ever sees multi-frame gaps.
        val gate = AnalysisFrameGate().apply { adaptiveCadence = true }
        val workNs = 75_000_000L
        val duty = simulate(gate, frame30, workNs) * workNs / 1e9
        assertTrue("duty $duty", duty <= AnalysisFrameGate.DUTY_BUDGET + 0.05)
    }

    @Test
    fun `stress caps the analysis rate`() {
        val moderate = AnalysisFrameGate().apply { stress = DeviceStress.MODERATE }
        assertTrue(simulate(moderate, frame30, workNs = 5_000_000L) <= 20.0)
        val high = AnalysisFrameGate().apply { stress = DeviceStress.HIGH }
        assertEquals(15.0, simulate(high, frame30, workNs = 5_000_000L), 0.2)
        val high60 = AnalysisFrameGate().apply { stress = DeviceStress.HIGH }
        assertTrue(simulate(high60, 16_666_667L, workNs = 5_000_000L) <= 15.1)
    }

    @Test
    fun `a stress change applies on the next frame`() {
        val gate = AnalysisFrameGate()
        assertTrue(gate.admit(0L))
        assertTrue(gate.admit(frame30))
        gate.stress = DeviceStress.HIGH
        assertEquals(false, gate.admit(2 * frame30))
        assertTrue(gate.admit(3 * frame30))
        gate.stress = DeviceStress.NONE
        assertTrue(gate.admit(4 * frame30))
    }

    @Test
    fun `a restarted stream is admitted at once`() {
        val gate = AnalysisFrameGate().apply { stress = DeviceStress.HIGH }
        assertTrue(gate.admit(10 * frame30))
        assertTrue(gate.admit(frame30))
    }
}
