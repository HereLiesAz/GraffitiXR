// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/CameraPreview.kt
package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@Composable
fun rememberCameraController(): LifecycleCameraController {
    val context = LocalContext.current
    return remember {
        LifecycleCameraController(context).apply {
            // IMAGE_ANALYSIS is enabled unconditionally alongside IMAGE_CAPTURE — this is the one
            // shared controller for the non-AR modes AND for AR's standalone SphereSLAM path
            // when ARCore is unavailable. Overlay also uses live analysis for its homography
            // fallback. Enabling the use case is not the cost:
            // nothing runs per-frame until a caller actually attaches an analyzer via
            // setImageAnalysisAnalyzer. Standalone SphereSLAM AR and the legacy non-ARCore
            // homography Overlay path both attach one when active.
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE or LifecycleCameraController.IMAGE_ANALYSIS)
            // Latency over throughput, stated rather than inherited from CameraX's default. Both
            // analyzers (standalone SphereSLAM, homography Overlay) are synchronous, so with
            // KEEP_ONLY_LATEST at most one frame waits while one is analysed: a slow KPM match
            // drops stale frames instead of queueing them, and the effective analysis rate adapts
            // to the match cost by itself. STRATEGY_BLOCK_PRODUCER here would queue up to the
            // image-queue depth and add that many frames of lag. Enforced by
            // tools/check_sphereslam_architecture.py.
            imageAnalysisBackpressureStrategy =
                androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            initializationFuture.addListener({
                cameraControl?.let { control ->
                    val c2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
                    val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                    builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                    c2Control.setCaptureRequestOptions(builder.build())
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(context))
        }
    }
}

/**
 * Take a still with the controller's already-bound ImageCapture use-case and decode it into a
 * [Bitmap] rotated to display orientation. Suspends until CameraX completes; no disk I/O.
 *
 * Used by the export path in Overlay mode — takePicture yields the sensor-quality still, and the
 * editor stacks the layers on top at scaled positions.
 */
suspend fun LifecycleCameraController.takePictureAsBitmap(context: Context): Bitmap =
    suspendCancellableCoroutine { cont ->
        takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        // JPEG bytes come out of ImageCapture; decode + apply the sensor rotation
                        // ImageCapture provides so the result matches display orientation.
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val rotationDeg = image.imageInfo.rotationDegrees
                        val out = if (rotationDeg != 0) {
                            val m = Matrix().apply { postRotate(rotationDeg.toFloat()) }
                            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                                if (it !== raw) raw.recycle()
                            }
                        } else raw
                        // suspendCancellableCoroutine ignores `resume` after cancellation, so the
                        // decoded bitmap would leak (no caller to take ownership + recycle it).
                        // Free the native pixel memory explicitly on the cancelled path.
                        if (cont.isActive) cont.resume(out) else out.recycle()
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }

@Composable
fun CameraPreview(
    controller: LifecycleCameraController,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                this.controller = controller
                // FIT_CENTER, not the default FILL_CENTER: both CameraX tracking consumers
                // (SphereSlamStandaloneOverlay for AR and HomographyFallbackOverlay for Overlay)
                // solve pose against the ImageAnalysis frame's own aspect ratio and draw with a
                // GL viewport letterboxed to match FIT_CENTER exactly (see
                // HomographyOverlayRenderer's doc). FILL_CENTER's crop has no such matching
                // counterpart on the GL side, which stretched/mis-scaled the tracked overlay
                // relative to what this preview actually shows.
                scaleType = PreviewView.ScaleType.FIT_CENTER
            }
        },
        update = { view ->
            controller.bindToLifecycle(lifecycleOwner)
        },
        modifier = modifier,
        onRelease = {
            controller.unbind()
        }
    )
}
