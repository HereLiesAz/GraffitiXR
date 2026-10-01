package com.hereliesaz.graffitixr.feature.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneObservationAgePolicyTest {

    @Test
    fun realtimeTimestampProducesAbsoluteAgeAndStaleGate() {
        val policy = StandaloneObservationAgePolicy(
            StandaloneObservationAgeConfig(maxRealtimeAgeMs = 250f),
        )
        val fresh = policy.evaluate(
            frameTimestampNs = 1_000_000_000L,
            nowElapsedRealtimeNs = 1_200_000_000L,
            source = StandaloneCameraTimestampSource.REALTIME,
        )
        assertEquals(200f, fresh.ageMs!!, 0.001f)
        assertFalse(fresh.stale)

        val stale = policy.evaluate(
            frameTimestampNs = 1_000_000_000L,
            nowElapsedRealtimeNs = 1_251_000_000L,
            source = StandaloneCameraTimestampSource.REALTIME,
        )
        assertTrue(stale.stale)
    }

    @Test
    fun unknownTimestampSourceNeverInventsAbsoluteAge() {
        val result = StandaloneObservationAgePolicy().evaluate(
            frameTimestampNs = 10L,
            nowElapsedRealtimeNs = 999_999_999L,
            source = StandaloneCameraTimestampSource.UNKNOWN,
        )
        assertNull(result.ageMs)
        assertFalse(result.stale)
    }

    @Test
    fun invalidOrFutureRealtimeTimestampDoesNotCreateBogusAge() {
        val policy = StandaloneObservationAgePolicy()
        assertNull(
            policy.evaluate(0L, 1_000L, StandaloneCameraTimestampSource.REALTIME).ageMs,
        )
        assertNull(
            policy.evaluate(2_000L, 1_000L, StandaloneCameraTimestampSource.REALTIME).ageMs,
        )
    }
}
