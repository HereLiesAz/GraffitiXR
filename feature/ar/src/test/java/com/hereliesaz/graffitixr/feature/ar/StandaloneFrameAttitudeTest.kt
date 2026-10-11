package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StandaloneFrameAttitudeTest {

    private fun aboutZ(deg: Double): FloatArray {
        val half = Math.toRadians(deg / 2.0)
        return floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat())
    }

    private val history = AttitudeExtrapolator().apply {
        push(aboutZ(0.0), 0L)
        push(aboutZ(20.0), 20_000_000L)
        push(aboutZ(40.0), 40_000_000L)
    }
    private val latest = AttitudeSample(aboutZ(40.0), 40_000_000L, accuracy = 3)

    @Test
    fun `realtime frame timestamps read the attitude at capture time`() {
        val sample = StandaloneFrameAttitude.atCapture(latest, history, 10_000_000L, true, FloatArray(4))!!
        assertEquals(10_000_000L, sample.timestampNs)
        assertEquals(3, sample.accuracy)
        val q = sample.quaternion
        val expected = aboutZ(10.0)
        for (i in 0..3) assertEquals(expected[i], q[i], 1e-4f)
    }

    @Test
    fun `non-realtime timestamps, no history or no sample fall back to the latest sample`() {
        assertSame(latest, StandaloneFrameAttitude.atCapture(latest, history, 10_000_000L, false, FloatArray(4)))
        assertSame(latest, StandaloneFrameAttitude.atCapture(latest, null, 10_000_000L, true, FloatArray(4)))
        assertSame(latest, StandaloneFrameAttitude.atCapture(latest, AttitudeExtrapolator(), 10_000_000L, true, FloatArray(4)))
        assertNull(StandaloneFrameAttitude.atCapture(null, history, 10_000_000L, true, FloatArray(4)))
    }

    @Test
    fun `the scratch buffer is not aliased by the returned sample`() {
        val scratch = FloatArray(4)
        val first = StandaloneFrameAttitude.atCapture(latest, history, 10_000_000L, true, scratch)!!
        StandaloneFrameAttitude.atCapture(latest, history, 30_000_000L, true, scratch)
        val expected = aboutZ(10.0)
        for (i in 0..3) assertEquals(expected[i], first.quaternion[i], 1e-4f)
    }
}
