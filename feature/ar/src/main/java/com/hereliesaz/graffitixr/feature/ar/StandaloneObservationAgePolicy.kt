package com.hereliesaz.graffitixr.feature.ar

internal enum class StandaloneCameraTimestampSource {
    REALTIME,
    UNKNOWN,
}

data class StandaloneObservationAgeConfig(
    val maxRealtimeAgeMs: Float = 250f,
) {
    init {
        require(maxRealtimeAgeMs.isFinite() && maxRealtimeAgeMs > 0f)
    }
}

data class StandaloneObservationAge(
    val ageMs: Float?,
    val stale: Boolean,
)

/**
 * CameraX forwards the underlying camera timestamp. Absolute age is safe to compare with
 * SystemClock.elapsedRealtimeNanos() only when Camera2 declares SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME.
 * UNKNOWN is intentionally not guessed or offset-calibrated from callback arrival time.
 */
internal class StandaloneObservationAgePolicy(
    private val config: StandaloneObservationAgeConfig = StandaloneObservationAgeConfig(),
) {
    fun evaluate(
        frameTimestampNs: Long,
        nowElapsedRealtimeNs: Long,
        source: StandaloneCameraTimestampSource,
    ): StandaloneObservationAge {
        if (
            source != StandaloneCameraTimestampSource.REALTIME ||
            frameTimestampNs <= 0L ||
            nowElapsedRealtimeNs < frameTimestampNs
        ) {
            return StandaloneObservationAge(ageMs = null, stale = false)
        }

        val ageMs = (nowElapsedRealtimeNs - frameTimestampNs).toFloat() / 1_000_000f
        return StandaloneObservationAge(
            ageMs = ageMs,
            stale = ageMs > config.maxRealtimeAgeMs,
        )
    }
}
