// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/OverlayGyroStabilizer.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.hereliesaz.sphereslam.camera.CameraFillCenter
import com.hereliesaz.sphereslam.camera.CameraIntrinsicsEstimator
import com.hereliesaz.sphereslam.attitude.GameRotationAttitudeSource
import com.hereliesaz.sphereslam.gyro.GyroCompensationMath
import com.hereliesaz.sphereslam.math.RotationMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Why Overlay's Gyro toggle let go on its own (the caller turns the toggle off and says so). */
enum class OverlayGyroRelease {
    /** Rotation since the reference passed [GyroCompensationMath.DEFAULT_RELEASE_DEGREES]. */
    MOVED,

    /** No game-rotation-vector sensor, or it never delivered a sample. */
    SENSOR_UNAVAILABLE,
}

/**
 * Overlay ▸ Gyro: holds the drawn design still on the wall against tiny tripod vibrations, from
 * sensor data alone.
 *
 * While [active], this captures the device orientation once as the reference (SphereSLAM's
 * [GameRotationAttitudeSource]), then every frame reads how far the phone has turned since
 * ([RotationMath.cameraRotationDelta], in body axes) and publishes the screen homography
 * ([GyroCompensationMath]) that carries the design along with the wall.
 * MainScreen applies it as an extra transform OUTSIDE Overlay's own ModeAdjustment, so the user's
 * edits and adjustments are untouched — turn Gyro off and the returned state goes back to null, i.e.
 * the design's normal Overlay placement.
 *
 * **Pure rotation only.** The infinite homography `K·R·K⁻¹` is exact for a camera that rotates in
 * place, whatever the scene depth, which is what a tripod allows. Translation is not measured (no
 * sensor here gives it without accelerometer double integration), so the parallax term
 * `t·nᵀ/d` is passed as null.
 *
 * **No depth.** Depth (`d` of the parallax term) matters only once translation is measured. An
 * earlier version ran MiDaS on an ImageCapture still every 15 s and logged the result without using
 * it — a still capture plus an inference per interval for nothing on screen, and MiDaS's relative
 * inverse depth could never stand in for a metric `d` anyway. It was removed; when translation is
 * added, depth (metric, not MiDaS) belongs with it.
 *
 * **Release.** Rotating past [GyroCompensationMath.DEFAULT_RELEASE_DEGREES] (the tripod was
 * bumped or the phone picked up) calls [onRelease] with [OverlayGyroRelease.MOVED] instead of
 * dragging the design several degrees on an unverifiable reading. A display-rotation change —
 * including the 90°<->270° flip between the two landscape orientations, which is NOT a configuration
 * change and so never reaches a `LocalConfiguration`-keyed read — is tracked with a
 * [DisplayManager.DisplayListener] and restarts the effect with a fresh reference. When [active] goes false — the toggle, or leaving Overlay — the
 * effect is cancelled and the sensor listener is unregistered.
 *
 * @return the row-major 3x3 homography to draw the design through, or null for "no compensation".
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun rememberOverlayGyroCompensation(
    active: Boolean,
    cameraController: LifecycleCameraController,
    viewWidth: Int,
    viewHeight: Int,
    onRelease: (OverlayGyroRelease) -> Unit,
): State<FloatArray?> {
    val context = LocalContext.current
    val displayRotationDeg = rememberDisplayRotationDegrees()
    val homography = remember { mutableStateOf<FloatArray?>(null) }
    val currentOnRelease by rememberUpdatedState(onRelease)

    LaunchedEffect(active, displayRotationDeg, viewWidth, viewHeight) {
        homography.value = null
        if (!active || viewWidth <= 0 || viewHeight <= 0) return@LaunchedEffect
        val appContext = context.applicationContext
        val attitude = GameRotationAttitudeSource(appContext)
        if (!attitude.isAvailable) {
            currentOnRelease(OverlayGyroRelease.SENSOR_UNAVAILABLE)
            return@LaunchedEffect
        }
        try {
            attitude.start()
            // The reference stays in pure BODY axes; GyroCompensationMath does the display remap
            // itself, tested per Surface.ROTATION_*. The first sample lands ~20 ms after start().
            val reference = withTimeoutOrNull(REFERENCE_TIMEOUT_MS) {
                var sample = attitude.latestSample()
                while (sample == null) {
                    delay(SAMPLE_POLL_MS)
                    sample = attitude.latestSample()
                }
                sample.quaternion
            }
            if (reference == null) {
                currentOnRelease(OverlayGyroRelease.SENSOR_UNAVAILABLE)
                return@LaunchedEffect
            }

            val intrinsics = withContext(Dispatchers.Default) {
                screenIntrinsicsFor(appContext, cameraController, viewWidth, viewHeight)
            }

            while (isActive) {
                withFrameNanos { }
                val now = attitude.latestSample() ?: continue
                val deltaBody = RotationMath.cameraRotationDelta(reference, now.quaternion)
                when (
                    val result = GyroCompensationMath.compensate(
                        deltaBody = deltaBody,
                        displayRotationDeg = displayRotationDeg,
                        screenIntrinsics = intrinsics,
                        // Translation is not measured — see the class doc. The parallax term stays off.
                        translationOverDepth = null,
                    )
                ) {
                    is GyroCompensationMath.Result.Within -> homography.value = result.homography
                    is GyroCompensationMath.Result.Exceeded -> {
                        Timber.i("OverlayGyro: released, rotated %.2f°", result.angleDegrees)
                        homography.value = null
                        currentOnRelease(OverlayGyroRelease.MOVED)
                        return@LaunchedEffect
                    }
                }
            }
        } finally {
            attitude.stop()
            homography.value = null
        }
    }
    return homography
}

/** `Surface.ROTATION_*` as the clockwise degrees [GyroCompensationMath] takes. */
internal fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun readDisplayRotationDegrees(view: View): Int =
    surfaceRotationToDegrees(view.display?.rotation ?: Surface.ROTATION_0)

/**
 * The current display rotation in degrees, kept live.
 *
 * This used to be `remember(configuration, view) { view.display.rotation }`. The activity handles
 * orientation changes in place (`configChanges=orientation`), and turning the phone from landscape
 * to the OPPOSITE landscape (90°<->270°) is not a configuration change at all — the `orientation`
 * config value is "landscape" on both sides — so the cached rotation went stale and the homography
 * was remapped for the wrong side, mirroring the compensation. A [DisplayManager.DisplayListener]
 * fires for every rotation change of this view's display, so the value is re-read there; the
 * configuration is kept as a second trigger for the cases that do change it.
 */
@Composable
private fun rememberDisplayRotationDegrees(): Int {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    var rotation by remember(view) { mutableIntStateOf(readDisplayRotationDegrees(view)) }
    DisposableEffect(view, context, configuration) {
        // Resync first: a change between the initial read and the listener registering is not lost.
        rotation = readDisplayRotationDegrees(view)
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == view.display?.displayId) rotation = readDisplayRotationDegrees(view)
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }
    return rotation
}

/** How long to wait for the rotation sensor's first sample before giving up. */
private const val REFERENCE_TIMEOUT_MS = 1_000L
private const val SAMPLE_POLL_MS = 16L

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
    // CameraPreview shows the frame FILL_CENTER, so a screen pixel is the covering scale's worth of
    // frame — the fit scale would under-state the focal length and under-compensate.
    return if (estimated != null) {
        CameraFillCenter.screenIntrinsics(estimated, viewWidth, viewHeight)
    } else {
        Timber.w("OverlayGyro: no camera intrinsics; using a nominal field of view")
        CameraFillCenter.screenIntrinsicsFallback(viewWidth, viewHeight)
    }
}
