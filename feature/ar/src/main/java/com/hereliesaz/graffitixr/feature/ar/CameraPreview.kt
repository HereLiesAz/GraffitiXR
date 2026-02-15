package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraController {
    internal var onCaptureRequested: (() -> Unit)? = null

    fun takePicture() {
        onCaptureRequested?.invoke()
    }
}

@Composable
fun rememberCameraController(): CameraController {
    return remember { CameraController() }
}

@Composable
fun CameraPreview(
    controller: CameraController,
    onPhotoCaptured: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val previewView = remember { PreviewView(context) }
    
    // Use case: ImageCapture. We intentionally don't set OUTPUT_FORMAT_JPEG explicitly 
    // because some devices/versions might default to it or support only it with minimize latency,
    // but we will verify the format in the callback.
    val imageCapture = remember { 
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build() 
    }

    // Connect controller
    DisposableEffect(controller) {
        controller.onCaptureRequested = {
            // We use the main executor for the callback itself to simplify UI interaction
            // The actual image capture work happens on the camera thread + background IO
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            // Convert ImageProxy to Bitmap
                            val bitmap = imageProxyToBitmap(image)
                            onPhotoCaptured(bitmap)
                        } catch (e: Exception) {
                            Log.e("CameraPreview", "Failed to process image: ${e.message}", e)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraPreview", "Photo capture failed: ${exception.message}", exception)
                    }
                }
            )
        }
        onDispose {
            controller.onCaptureRequested = null
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = context.getCameraProvider()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch(exc: Exception) {
            Log.e("CameraPreview", "Use case binding failed", exc)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
    cameraProviderFuture.addListener({
        continuation.resume(cameraProviderFuture.get())
    }, ContextCompat.getMainExecutor(this))
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    // Explicitly check format
    if (image.format != ImageFormat.JPEG) {
        throw IllegalArgumentException("Expected JPEG format, but got ${image.format}. YUV conversion is not yet implemented.")
    }

    val buffer: ByteBuffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IllegalArgumentException("Failed to decode JPEG byte array")
    
    // Rotate if needed
    val rotation = image.imageInfo.rotationDegrees
    if (rotation != 0) {
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    return bitmap
}
