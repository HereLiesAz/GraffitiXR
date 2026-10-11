package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor

/**
 * Which camera-from-map view the standalone artwork is drawn with on a given GL frame.
 *
 * The vision pose arrives at analyzer rate, 40–150+ ms after its frame. SphereSLAM's
 * `PhotosphereTrackingSession` publishes every rendered pose to a [DisplayPosePredictor] together
 * with its frame's attitude (and clears it whenever a frame yields no pose); on the GL thread the
 * predictor rotates that pose by the attitude change up to the draw time, read from the same
 * sensor's [AttitudeExtrapolator]. Rotation — the dominant motion of a hand-held phone — then
 * follows the device at display rate while translation still updates at vision rate.
 *
 * Falls back to [lastVisionView] (the pose last pushed to the renderer) whenever there is no
 * prediction: no predictor/attitude wired (co-op peer tracking, homography fallback), no held pose
 * (cleared on loss), or no attitude sample yet. Pure and allocation-free, for the GL thread.
 */
internal object ArtworkPosePrediction {

    /**
     * The view to draw at [nowNs] (`SystemClock.elapsedRealtimeNanos()` base): the prediction,
     * written into [scratch] (length >= 16) and returned, or [lastVisionView] unchanged.
     */
    fun viewForDraw(
        predictor: DisplayPosePredictor?,
        attitude: AttitudeExtrapolator?,
        nowNs: Long,
        lastVisionView: FloatArray,
        scratch: FloatArray,
    ): FloatArray {
        if (predictor == null || attitude == null) return lastVisionView
        return if (predictor.predictInto(attitude, nowNs, scratch)) scratch else lastVisionView
    }
}
