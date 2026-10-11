// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/BridgedHomographyTracker.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.sphereslam.attitude.AttitudeSample
import com.hereliesaz.sphereslam.attitude.DeviceCameraRotation
import com.hereliesaz.sphereslam.attitude.GameRotationAttitudeSource
import com.hereliesaz.sphereslam.camera.CameraIntrinsics
import com.hereliesaz.sphereslam.reloc.PlanarTracker
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Overlay's planar live tracker: a thin Android adapter over SphereSLAM's [PlanarTracker], which
 * does the vision (ORB, Lowe ratio, `solvePnPRansac` + IPPE refine against the captured reference
 * stretched onto the design quad's half-extents, with the inlier count/ratio/spread gates) and the
 * short gyro bridge (a lost lock is held for at most [PlanarTracker.Config.maxBridgeMs] by rotating
 * the last pose about its camera centre, with linearly decaying confidence).
 *
 * What stays here is the Android side: the attitude comes from SphereSLAM's
 * [GameRotationAttitudeSource] (`TYPE_GAME_ROTATION_VECTOR`), the body→camera rotation of the
 * tracked image is [DeviceCameraRotation.cameraFromDevice]`(sensorOrientation, rotationDegrees)`, and
 * the captured reference `Bitmap` is converted to luma (`COLOR_RGBA2GRAY`, as the native tracker this
 * replaced did).
 *
 * [PlanarTracker] needs OpenCV's Java native library; [NativeLibLoader] loads it (it is also loaded
 * at application start). The tracker is not thread-safe, so every call here is synchronized: the
 * reference is set off the main thread while the CameraX analysis worker tracks.
 *
 * [HomographyFallbackOverlay] wires this to CameraX through [HomographyTrackingAnalyzer].
 */
class BridgedHomographyTracker internal constructor(
    private val tracker: PlanarTracker,
    private val attitudeSource: GameRotationAttitudeSource?,
    private val latestAttitude: () -> AttitudeSample?,
) : AutoCloseable {

    constructor(context: Context, maxBridgeMs: Long = DEFAULT_MAX_BRIDGE_MS) :
        this(GameRotationAttitudeSource(context), maxBridgeMs)

    private constructor(source: GameRotationAttitudeSource, maxBridgeMs: Long) :
        this(PlanarTracker(PlanarTracker.Config(maxBridgeMs = maxBridgeMs)), source, source::latestSample)

    init {
        NativeLibLoader.loadAll()
    }

    private var closed = false

    /** Starts the attitude sensor. Call from the same lifecycle scope that will call [trackFrame]. */
    fun start() {
        attitudeSource?.start()
    }

    /** Stops the attitude sensor. Call when the fallback tracking session ends. */
    fun stop() {
        attitudeSource?.stop()
    }

    /**
     * Replace the reference with [referenceBitmap] (`ARGB_8888`) stretched onto
     * `[-objectHalfW, objectHalfW] × [-objectHalfH, objectHalfH]` — pass the SAME half-extents the
     * renderer draws the design quad at, so every tracked pose comes back in those units. Returns
     * false (no reference) when the image is too plain/blurry to track.
     */
    @Synchronized
    fun setReference(referenceBitmap: Bitmap, objectHalfW: Float, objectHalfH: Float): Boolean {
        require(referenceBitmap.config == Bitmap.Config.ARGB_8888) { "bitmap must be ARGB_8888" }
        require(objectHalfW > 0f && objectHalfH > 0f) { "objectHalfW/H must be positive" }
        if (closed) return false
        val luma = lumaOf(referenceBitmap)
        return tracker.setReference(luma, referenceBitmap.width, referenceBitmap.height, objectHalfW, objectHalfH)
    }

    @Synchronized
    fun hasReference(): Boolean = !closed && tracker.hasReference

    @Synchronized
    fun reset() {
        if (!closed) tracker.reset()
    }

    /**
     * Track one display-upright luma frame, bridging through a brief vision failure with gyro
     * rotation.
     *
     * @param intrinsics the tracked frame's intrinsics (its size is the frame size).
     * @param rotationDeg CameraX `rotationDegrees` for THIS frame — the clockwise rotation applied to
     *   the buffer to produce [luma].
     * @param sensorOrientationDeg the camera's `SENSOR_ORIENTATION`.
     * @return a vision-tracked pose, a gyro-bridged hold of the last one, or null.
     */
    @Synchronized
    fun trackFrame(
        luma: ByteArray,
        width: Int,
        height: Int,
        intrinsics: CameraIntrinsics,
        rotationDeg: Int,
        sensorOrientationDeg: Int,
    ): PlanarTracker.Pose? {
        if (closed) return null
        return tracker.track(
            luma = luma,
            width = width,
            height = height,
            intrinsics = intrinsics,
            // Sampled before the synchronous solve, so a committed bridge reference sits at this
            // frame's capture time.
            attitude = latestAttitude(),
            cameraFromDevice = DeviceCameraRotation.cameraFromDevice(sensorOrientationDeg, rotationDeg),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        tracker.close()
    }

    private fun lumaOf(bitmap: Bitmap): ByteArray {
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val out = ByteArray(bitmap.width * bitmap.height)
            gray.get(0, 0, out)
            return out
        } finally {
            rgba.release()
            gray.release()
        }
    }

    companion object {
        const val DEFAULT_MAX_BRIDGE_MS = 400L
    }
}
