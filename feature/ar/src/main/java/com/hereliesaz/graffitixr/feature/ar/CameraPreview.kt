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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

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
    val lifecycleOwner = LocalLifecycleOwner.current

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