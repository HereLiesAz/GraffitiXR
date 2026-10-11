package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.AttitudeSample

/**
 * The device attitude handed to SphereSLAM's tracking session as `Frame.attitude`.
 *
 * The session anchors the gyro bridge reference and the display pose predictor
 * ([com.hereliesaz.sphereslam.attitude.DisplayPosePredictor.update], whose KDoc asks for the
 * attitude "at that frame's capture time") on this value. A frame can wait in the analysis queue
 * and the analyzer for tens of milliseconds, so the latest sensor sample at `analyze()` time is
 * already that much ahead of the image; during a pan the predicted artwork would then be rotated
 * from the wrong reference.
 *
 * When the camera's timestamps share the sensor's clock (Camera2 `SENSOR_INFO_TIMESTAMP_SOURCE`
 * `REALTIME`, i.e. `elapsedRealtimeNanos`, the `SensorEvent.timestamp` base), the attitude AT the
 * frame timestamp is read from the same sensor's history ([AttitudeExtrapolator.quaternionAt]:
 * interpolated inside it, briefly extrapolated past it). Otherwise (an `UNKNOWN` source is not
 * guaranteed to be comparable and there is no reliable conversion) — or without a history or any
 * sample — it falls back to [latest], the documented approximation.
 */
internal object StandaloneFrameAttitude {

    /**
     * @param latest the sensor's newest sample (null before the first one).
     * @param history the same sensor's timestamped history, or null when none is wired.
     * @param frameTimestampNs the camera frame's timestamp.
     * @param frameTimestampIsRealtime whether [frameTimestampNs] is on the `elapsedRealtimeNanos` base.
     * @param scratch reusable `[x, y, z, w]` buffer of length 4; its contents are copied out.
     * @return the attitude at capture time stamped with [frameTimestampNs], or [latest].
     */
    fun atCapture(
        latest: AttitudeSample?,
        history: AttitudeExtrapolator?,
        frameTimestampNs: Long,
        frameTimestampIsRealtime: Boolean,
        scratch: FloatArray,
    ): AttitudeSample? {
        if (latest == null || history == null || !frameTimestampIsRealtime) return latest
        if (!history.quaternionAt(frameTimestampNs, scratch)) return latest
        return AttitudeSample(scratch, frameTimestampNs, latest.accuracy)
    }
}
