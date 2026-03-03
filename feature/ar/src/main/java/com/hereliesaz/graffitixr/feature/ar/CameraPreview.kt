package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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

    /** Called when the camera is unbound (lifecycle stop). */
    internal fun onCameraReleased() {
        cameraControl = null
    }

    /** Enables or disables the camera torch. No-op if camera isn't bound yet. */
    fun enableTorch(enabled: Boolean) {
        cameraControl?.enableTorch(enabled)
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
    modifier: Modifier = Modifier,
    onAnalyzerFrame: ((ByteBuffer, Int, Int) -> Unit)? = null,
    onLightUpdate: ((Float) -> Unit)? = null
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
                            // Convert ImageProxy to Bitmap using Context for YUV fallback
                            val bitmap = imageProxyToBitmap(context, image)
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

    // Background executor for image analysis
    val hasAnalysis = onAnalyzerFrame != null || onLightUpdate != null
    val analysisExecutor = remember(hasAnalysis) {
        if (hasAnalysis) Executors.newSingleThreadExecutor() else null
    }
    DisposableEffect(analysisExecutor) {
        onDispose { analysisExecutor?.shutdown() }
    }

    // ImageAnalysis use case.
    // If onLightUpdate is provided, use DualAnalyzer — it handles both stereo and ambient light
    // with its own 200 ms light throttle; backpressure replaces the manual frame counter.
    // Otherwise fall back to the legacy manual-throttle path (stereo only).
    val imageAnalysis = remember(onAnalyzerFrame, onLightUpdate) {
        val executor = analysisExecutor ?: return@remember null
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        if (onLightUpdate != null) {
            analysis.setAnalyzer(executor, DualAnalyzer(
                onLightUpdate = onLightUpdate,
                onSlamFrame = onAnalyzerFrame
            ))
        } else if (onAnalyzerFrame != null) {
            var frameIndex = 0
            analysis.setAnalyzer(executor) { image ->
                if (frameIndex++ % 10 == 0) {
                    onAnalyzerFrame(image.planes[0].buffer, image.width, image.height)
                }
                image.close()
            }
        }
        analysis
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = context.getCameraProvider()
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            val useCases = listOfNotNull(preview, imageCapture, imageAnalysis).toTypedArray()
            val camera: Camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases
            )
            controller.onCameraReady(camera.cameraControl)
        } catch(exc: Exception) {
            Log.e("CameraPreview", "Use case binding failed", exc)
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose { controller.onCameraReleased() }
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

private fun imageProxyToBitmap(context: Context, image: ImageProxy): Bitmap {
    val bitmap: Bitmap

    if (image.format == ImageFormat.JPEG) {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Failed to decode JPEG byte array")
    } else if (image.format == ImageFormat.YUV_420_888) {
        // FIX: Utilize YuvToRgbConverter to prevent crashes on devices that ignore JPEG request
        bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val converter = YuvToRgbConverter(context)
        image.image?.let {
            converter.yuvToRgb(it, bitmap)
        } ?: throw IllegalArgumentException("ImageProxy does not contain a valid Image")
    } else {
        throw IllegalArgumentException("Unsupported image format: ${image.format}")
    }

    // Rotate if needed
    val rotation = image.imageInfo.rotationDegrees
    if (rotation != 0) {
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    return bitmap
}