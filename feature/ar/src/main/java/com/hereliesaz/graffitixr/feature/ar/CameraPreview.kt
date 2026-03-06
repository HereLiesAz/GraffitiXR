// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/CameraPreview.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.graffitixr.common.util.YuvToRgbConverter
import com.hereliesaz.graffitixr.feature.ar.util.DualAnalyzer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraController {
    internal var onCaptureRequested: (() -> Unit)? = null
    private var cameraControl: CameraControl? = null

    fun takePicture() {
        onCaptureRequested?.invoke()
    }

    /** Called by CameraPreview once the camera is bound. */
    internal fun onCameraReady(control: CameraControl) {
        cameraControl = control
    }

typealias CameraController = LifecycleCameraController

@Composable
fun rememberCameraController(): CameraController {
    val context = LocalContext.current
    return remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(
                androidx.camera.view.CameraController.IMAGE_CAPTURE or
                        androidx.camera.view.CameraController.IMAGE_ANALYSIS
            )
        }
    }
}

@Composable
fun CameraPreview(
    controller: CameraController,
    onPhotoCaptured: (Bitmap) -> Unit,
    onAnalyzerFrame: (ImageProxy) -> Unit,
    onLightUpdate: (Float) -> Unit,
    modifier: Modifier = Modifier,
    arViewModel: ArViewModel
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, controller) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = controller
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        modifier = modifier
    )
}