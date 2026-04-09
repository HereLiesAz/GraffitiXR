// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/CameraPreview.kt
package com.hereliesaz.graffitixr.feature.ar

import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner

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