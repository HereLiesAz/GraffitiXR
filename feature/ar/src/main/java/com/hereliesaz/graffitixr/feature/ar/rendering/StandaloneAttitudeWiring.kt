package com.hereliesaz.graffitixr.feature.ar.rendering

import com.hereliesaz.sphereslam.attitude.AttitudeExtrapolator
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.DisplayPosePredictor
import com.hereliesaz.sphereslam.camera.CameraFillCenter
import com.hereliesaz.sphereslam.camera.CameraIntrinsics

/**
 * The attitude half of [HomographyOverlayRenderer], kept free of GL so the app's wiring is
 * unit-testable: the tracking session publishes each rendered pose to [predictor] (anchored at its
 * frame's capture-time attitude and the frame's `cameraFromDevice`); on every draw the artwork view
 * is that pose rotated to the draw time ([viewForDraw]) and the coverage glow's camera rotation is
 * the same sensor's attitude composed with the display-upright camera frame ([worldFromCameraAt]).
 *
 * All setters are safe from any thread (whole-value `@Volatile` swaps); the readers run on the GL
 * thread and allocate nothing.
 */
internal class StandaloneAttitudeWiring {

    @Volatile var extrapolator: AttitudeExtrapolator? = null
        private set

    @Volatile var predictor: DisplayPosePredictor? = null
        private set

    /** Row-major GL-camera-from-device of the display-upright preview; replaced whole, never mutated. */
    @Volatile private var cameraFromDisplay: FloatArray =
        DeviceCameraRotation.cameraFromDeviceForDisplayFrame(0)

    /**
     * Attach the attitude history and, optionally, the predictor the tracking session publishes to
     * (which must be fed from the same sensor). Null [extrapolator] disables both the glow rotation
     * and prediction.
     */
    fun set(extrapolator: AttitudeExtrapolator?, predictor: DisplayPosePredictor?) {
        this.extrapolator = extrapolator
        this.predictor = predictor
    }

    /** The current display rotation in degrees (0/90/180/270): the glow's camera frame. */
    fun setDisplayRotation(displayRotationDeg: Int) {
        cameraFromDisplay = DeviceCameraRotation.cameraFromDeviceForDisplayFrame(displayRotationDeg)
    }

    /**
     * World-from-camera (row-major 3×3) of the display-upright preview camera at [timeNs]
     * (`elapsedRealtimeNanos` base) into [out]; false without an attitude source or sample.
     */
    fun worldFromCameraAt(timeNs: Long, out: FloatArray): Boolean =
        extrapolator?.worldFromCameraAt(timeNs, cameraFromDisplay, out) ?: false

    /** The artwork view for a draw at [nowNs]; see [ArtworkPosePrediction.viewForDraw]. */
    fun viewForDraw(nowNs: Long, lastVisionView: FloatArray, scratch: FloatArray): FloatArray =
        ArtworkPosePrediction.viewForDraw(
            predictor = predictor,
            attitude = extrapolator,
            nowNs = nowNs + ArtworkPosePrediction.ARTWORK_DRAW_LEAD_NS,
            lastVisionView = lastVisionView,
            scratch = scratch,
        )

    /** Drop the held prediction (tracking lost). */
    fun clearPrediction() {
        predictor?.clear()
    }
}

/**
 * Cache key for the coverage glow's lens: the on-screen (`FILL_CENTER`) intrinsics are rebuilt only
 * when the tracked frame's intrinsics or the surface size change. GL-thread only.
 */
internal class GlowLensCache {
    private var intrinsics: CameraIntrinsics? = null
    private var width = 0
    private var height = 0

    /**
     * The new on-screen intrinsics when ([frame], [surfaceWidth], [surfaceHeight]) differs from the
     * last call that had a frame, else null (nothing to rebuild). Also null when the crop is
     * undefined (e.g. a zero-sized surface); the key is still recorded so it is not retried per draw.
     */
    fun lensIntrinsicsIfChanged(
        frame: CameraIntrinsics?,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): CameraIntrinsics? {
        if (frame == null) return null
        if (frame == intrinsics && surfaceWidth == width && surfaceHeight == height) return null
        intrinsics = frame
        width = surfaceWidth
        height = surfaceHeight
        return CameraFillCenter.intrinsics(frame, surfaceWidth, surfaceHeight)
    }
}
