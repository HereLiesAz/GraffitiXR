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
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
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