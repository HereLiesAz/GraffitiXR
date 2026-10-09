// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/OverlayGyroStabilizer.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsicsEstimator
import com.hereliesaz.graffitixr.feature.ar.depth.DepthEstimator
import com.hereliesaz.graffitixr.feature.ar.util.OverlayGyroCompensationMath
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Why Overlay's Gyro toggle let go on its own (the caller turns the toggle off and says so). */
enum class OverlayGyroRelease {
    /** Rotation since the reference passed [OverlayGyroCompensationMath.DEFAULT_RELEASE_DEGREES]. */
    MOVED,

    /** No game-rotation-vector sensor, or it never delivered a sample. */
    SENSOR_UNAVAILABLE,
}

/**
 * Overlay ▸ Gyro: holds the drawn design still on the wall against tiny tripod vibrations, from
 * sensor data alone.
 *
 * While [active], this captures the device orientation once as the reference, then every frame reads
 * how far the phone has turned since ([GyroOrientationBridge.cameraRotationDelta]) and publishes the
 * screen homography ([OverlayGyroCompensationMath]) that carries the design along with the wall.
 * MainScreen applies it as an extra transform OUTSIDE Overlay's own ModeAdjustment, so the user's
 * edits and adjustments are untouched — turn Gyro off and the returned state goes back to null, i.e.
 * the design's normal Overlay placement.
 *
 * **Pure rotation only.** The infinite homography `K·R·K⁻¹` is exact for a camera that rotates in
 * place, whatever the scene depth, which is what a tripod allows. Translation is not measured (no
 * sensor here gives it without accelerometer double integration), so the parallax term
 * `t·nᵀ/d` is passed as null.
 *
 * **MiDaS.** [DepthEstimator] runs on a background dispatcher once right after the reference and then
 * at most every [DEPTH_INTERVAL_MS], on a downsampled ImageCapture still — never per frame, never on
 * the main thread. It samples the wall under the design's centre as a RELATIVE inverse depth
 * ([OverlayGyroCompensationMath.relativeInverseDepthAt]). That is the `d` of the parallax term, kept
 * ready for when translation exists; with translation off it changes nothing on screen, and MiDaS is
 * unitless anyway, so it could never stand in for a metric distance. Its estimator is this toggle's
 * own instance, closed when the toggle releases — ArViewModel's shared one is untouched.
 *
 * **Release.** Rotating past [OverlayGyroCompensationMath.DEFAULT_RELEASE_DEGREES] (the tripod was
 * bumped or the phone picked up) calls [onRelease] with [OverlayGyroRelease.MOVED] instead of
 * dragging the design several degrees on an unverifiable reading. A display-rotation change restarts
 * the effect with a fresh reference. When [active] goes false — the toggle, or leaving Overlay — the
 * effect is cancelled: the sensor listener is unregistered, the MiDaS loop stops and its ORT session
 * is closed.
 *
 * @param designCenter the design's centre on screen, as fractions of the view (for the MiDaS sample).
 * @return the row-major 3x3 homography to draw the design through, or null for "no compensation".
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun rememberOverlayGyroCompensation(
    active: Boolean,
    cameraController: LifecycleCameraController,
    viewWidth: Int,
    viewHeight: Int,
    designCenter: () -> Pair<Float, Float>,
    onRelease: (OverlayGyroRelease) -> Unit,
): State<FloatArray?> {
    val context = LocalContext.current
    val view = LocalView.current
    // Keyed on the configuration so an orientation change (handled in place: MainActivity declares
    // configChanges=orientation) re-reads the display rotation.
    val configuration = LocalConfiguration.current
    val displayRotationDeg = remember(configuration, view) {
        when (view.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
    val homography = remember { mutableStateOf<FloatArray?>(null) }
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentDesignCenter by rememberUpdatedState(designCenter)

    LaunchedEffect(active, displayRotationDeg, viewWidth, viewHeight) {
        homography.value = null
        if (!active || viewWidth <= 0 || viewHeight <= 0) return@LaunchedEffect
        val appContext = context.applicationContext
        val bridge = GyroOrientationBridge(appContext)
        if (!bridge.isAvailable) {
            currentOnRelease(OverlayGyroRelease.SENSOR_UNAVAILABLE)
            return@LaunchedEffect
        }
        var depthJob: Job? = null
        try {
            bridge.start()
            // The reference is committed at rotationDeg = 0 so cameraRotationDelta() stays in pure
            // BODY axes (its A = identity); OverlayGyroCompensationMath does the display remap
            // itself, tested per Surface.ROTATION_*. The first sample lands ~20 ms after start().
            val committed = withTimeoutOrNull(REFERENCE_TIMEOUT_MS) {
                var candidate = bridge.captureReferenceCandidate(0)
                while (candidate == null) {
                    delay(SAMPLE_POLL_MS)
                    candidate = bridge.captureReferenceCandidate(0)
                }
                bridge.commitReference(candidate)
                true
            }
            if (committed == null) {
                currentOnRelease(OverlayGyroRelease.SENSOR_UNAVAILABLE)
                return@LaunchedEffect
            }

            val intrinsics = withContext(Dispatchers.Default) {
                screenIntrinsicsFor(appContext, cameraController, viewWidth, viewHeight)
            }

            // MiDaS, very slowly, off the main thread. Child of this effect: cancelled with it, and
            // explicitly in `finally` so an auto-release stops it at once.
            depthJob = launch(Dispatchers.Default) {
                runDepthLoop(appContext, cameraController) { currentDesignCenter() }
            }

            while (isActive) {
                withFrameNanos { }
                val deltaBody = bridge.cameraRotationDelta() ?: continue
                when (
                    val result = OverlayGyroCompensationMath.compensate(
                        deltaBody = deltaBody,
                        displayRotationDeg = displayRotationDeg,
                        screenIntrinsics = intrinsics,
                        // Translation is not measured — see the class doc. The parallax term stays off.
                        translationOverDepth = null,
                    )
                ) {
                    is OverlayGyroCompensationMath.Result.Within -> homography.value = result.homography
                    is OverlayGyroCompensationMath.Result.Exceeded -> {
                        Timber.i("OverlayGyro: released, rotated %.2f°", result.angleDegrees)
                        homography.value = null
                        currentOnRelease(OverlayGyroRelease.MOVED)
                        return@LaunchedEffect
                    }
                }
            }
        } finally {
            depthJob?.cancel()
            bridge.stop()
            bridge.clearReference()
            homography.value = null
        }
    }
    return homography
}

/** Interval between MiDaS runs while Gyro is on — the surface in front of a tripod does not move. */
private const val DEPTH_INTERVAL_MS = 15_000L

/** How long to wait for the rotation sensor's first sample before giving up. */
private const val REFERENCE_TIMEOUT_MS = 1_000L
private const val SAMPLE_POLL_MS = 16L

/** Long side of the still handed to MiDaS (which resizes to 256 anyway). */
private const val DEPTH_STILL_MAX_DIM = 512

/** A nominal 4:3 frame in sensor orientation; only the f/width ratio survives into screen pixels. */
private const val NOMINAL_FRAME_W = 4000
private const val NOMINAL_FRAME_H = 3000

@OptIn(ExperimentalCamera2Interop::class)
private suspend fun screenIntrinsicsFor(
    context: Context,
    cameraController: LifecycleCameraController,
    viewWidth: Int,
    viewHeight: Int,
): FloatArray {
    // cameraInfo is a main-thread CameraX getter; the Camera2 characteristics read is not.
    val cameraId = withContext(Dispatchers.Main) {
        cameraController.cameraInfo?.let { Camera2CameraInfo.from(it).cameraId }
    }
    val estimated = cameraId?.let {
        CameraIntrinsicsEstimator.estimate(context, it, NOMINAL_FRAME_W, NOMINAL_FRAME_H)
    }
    return if (estimated != null) {
        OverlayGyroCompensationMath.screenIntrinsics(
            estimated.fx, estimated.fy, estimated.width, estimated.height, viewWidth, viewHeight,
        )
    } else {
        Timber.w("OverlayGyro: no camera intrinsics; using a nominal field of view")
        OverlayGyroCompensationMath.fallbackScreenIntrinsics(viewWidth, viewHeight)
    }
}

/**
 * Once now, then every [DEPTH_INTERVAL_MS]: grab a small still, run MiDaS, sample the surface under
 * the design. Runs on Dispatchers.Default; the estimator is closed in `finally` on this same
 * background thread, after any in-flight (synchronized, uninterruptible) inference returns — so a
 * release never blocks the main thread on ORT.
 */
private suspend fun runDepthLoop(
    context: Context,
    cameraController: LifecycleCameraController,
    designCenter: () -> Pair<Float, Float>,
) = kotlinx.coroutines.coroutineScope {
    val estimator = DepthEstimator(context)
    try {
        if (!estimator.load()) {
            Timber.w("OverlayGyro: depth unavailable (%s)", estimator.lastError)
            return@coroutineScope
        }
        while (isActive) {
            val still = withContext(Dispatchers.Main) {
                cameraController.takeDownsampledStill(depthStillExecutor, DEPTH_STILL_MAX_DIM)
            }
            if (still != null) {
                val map = try { estimator.estimate(still) } finally { still.recycle() }
                if (map != null) {
                    val (nx, ny) = designCenter()
                    val rel = OverlayGyroCompensationMath.relativeInverseDepthAt(
                        map.data, map.width, map.height, nx, ny,
                    )
                    // Logged, not applied: it would scale a translation term that is off (class doc).
                    Timber.d("OverlayGyro: surface relative inverse depth %.3f", rel ?: Float.NaN)
                }
            }
            delay(DEPTH_INTERVAL_MS)
        }
    } finally {
        estimator.close()
    }
}

/**
 * One idle background thread for the still's JPEG decode, shared for the process. Not shut down per
 * toggle: a capture cancelled mid-flight still delivers its callback later, and a shut-down executor
 * would reject it inside CameraX.
 */
private val depthStillExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

/**
 * Like [takePictureAsBitmap], but decodes on [executor] with power-of-two subsampling down to about
 * [maxDim] on the long side — MiDaS needs 256², and a full-resolution decode on the main executor
 * every few seconds would be a needless ~50 MB allocation and a dropped frame. Null on any capture
 * or decode failure (depth is best-effort). Must be called on the main thread (CameraX).
 */
private suspend fun LifecycleCameraController.takeDownsampledStill(
    executor: Executor,
    maxDim: Int,
): Bitmap? = suspendCancellableCoroutine { cont ->
    takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
                    val raw = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                    if (raw == null) {
                        if (cont.isActive) cont.resume(null)
                        return
                    }
                    val rotationDeg = image.imageInfo.rotationDegrees
                    val out = if (rotationDeg != 0) {
                        val m = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                            if (it !== raw) raw.recycle()
                        }
                    } else raw
                    if (cont.isActive) cont.resume(out) else out.recycle()
                } catch (t: Throwable) {
                    Timber.w(t, "OverlayGyro: still decode failed")
                    if (cont.isActive) cont.resume(null)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Timber.w(exception, "OverlayGyro: still capture failed")
                if (cont.isActive) cont.resume(null)
            }
        },
    )
}
