package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.util.PerspectiveProcessor
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer
import com.hereliesaz.sphereslam.SphereSlamStandaloneSession
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SPHERESLAM_DEFAULT_UNWARP_POINTS = listOf(
    Offset(0.25f, 0.25f),
    Offset(0.75f, 0.25f),
    Offset(0.75f, 0.75f),
    Offset(0.25f, 0.75f),
)

/**
 * First functional no-ARCore AR path.
 *
 * Layer this composable over [CameraPreview]. The artist captures and rectifies a textured wall
 * patch, then CameraX frames are fed to calibrated SphereSLAM/KPM. A successful match directly
 * supplies the wall-relative OpenGL view matrix used to render the design. Short visual dropouts are
 * bridged with the device's fused gyro orientation; longer loss clears the overlay and shows an
 * explicit reacquisition state.
 *
 * The initial target uses a normalized 1.0-unit reference width rather than inventing a physical
 * measurement. Registration is geometrically self-consistent because KPM translation and the
 * rendered quad use the same scale. Physical distance readouts remain disabled/undefined until a
 * measured wall scale is supplied.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun SphereSlamStandaloneOverlay(
    cameraController: LifecycleCameraController,
    designBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val strings = rememberAppStrings()
    val scope = rememberCoroutineScope()

    var rawCaptureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var unwarpPoints by remember { mutableStateOf(SPHERESLAM_DEFAULT_UNWARP_POINTS) }
    var referenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var referenceReady by remember { mutableStateOf(false) }
    var isTrackingLost by remember { mutableStateOf(true) }
    var fatalMessage by remember { mutableStateOf<String?>(null) }

    fun resetCapture() {
        rawCaptureBitmap = null
        referenceBitmap = null
        unwarpPoints = SPHERESLAM_DEFAULT_UNWARP_POINTS
        referenceReady = false
        isTrackingLost = true
        fatalMessage = null
    }

    val reference = referenceBitmap
    if (reference == null) {
        val raw = rawCaptureBitmap
        if (raw == null) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    fatalMessage?.let { message ->
                        Text(
                            text = message,
                            color = Color.White,
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    Button(
                        onClick = {
                            fatalMessage = null
                            scope.launch {
                                val result = runCatching {
                                    cameraController.takePictureAsBitmap(context)
                                }
                                rawCaptureBitmap = result.getOrElse {
                                    fatalMessage = "Couldn't capture the wall target — try again."
                                    null
                                }
                            }
                        },
                        modifier = Modifier.padding(bottom = 32.dp),
                    ) {
                        Text("Capture Wall Target")
                    }
                }
            }
        } else {
            UnwarpScreen(
                bitmap = raw,
                points = unwarpPoints,
                onUpdatePoints = { unwarpPoints = it },
                onConfirm = { points ->
                    scope.launch(Dispatchers.Default) {
                        val pixelPoints = points.map { Offset(it.x * raw.width, it.y * raw.height) }
                        val unwarped = PerspectiveProcessor.unwarpImage(raw, pixelPoints)
                        withContext(Dispatchers.Main) {
                            if (unwarped == null) {
                                fatalMessage = "Couldn't rectify that target — mark four clear corners."
                                rawCaptureBitmap = null
                            } else {
                                referenceBitmap = unwarped
                                rawCaptureBitmap = null
                            }
                        }
                    }
                },
                onCancel = {
                    rawCaptureBitmap = null
                    unwarpPoints = SPHERESLAM_DEFAULT_UNWARP_POINTS
                },
                strings = strings,
            )
        }
        return
    }

    val referenceImage = remember(reference) {
        SphereSlamStandaloneReferenceImage(
            luma = bitmapToLuma(reference),
            width = reference.width,
            height = reference.height,
            referenceWidthMeters = 1f,
            physicallyMetric = false,
        )
    }
    val glRenderer = remember(context) { HomographyOverlayRenderer(context) }

    LaunchedEffect(glRenderer, designBitmap) {
        designBitmap?.let(glRenderer::updateDesignBitmap)
    }

    val cameraId by produceState<String?>(initialValue = null, cameraController, reference) {
        while (value == null) {
            value = runCatching {
                cameraController.cameraInfo?.let { Camera2CameraInfo.from(it).cameraId }
            }.getOrNull()
            if (value == null) delay(50)
        }
    }

    DisposableEffect(cameraController, cameraId, referenceImage) {
        val id = cameraId
        if (id == null) {
            onDispose {}
        } else {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "sphereslam-standalone-camera").apply { isDaemon = true }
            }
            val analyzer = SphereSlamStandaloneTrackingAnalyzer(
                context = context,
                cameraId = id,
                referenceImage = referenceImage,
                onReferenceReady = { registered: SphereSlamStandaloneSession.Reference ->
                    val g = registered.geometry
                    glRenderer.setExtent(g.widthMeters * 0.5f, g.heightMeters * 0.5f)
                    referenceReady = true
                },
                onFrameTracked = { frame ->
                    isTrackingLost = frame == null
                    if (frame == null) {
                        glRenderer.clearPose()
                    } else {
                        glRenderer.updatePose(frame.viewMatrix, frame.projMatrix, frame.frameAspect)
                    }
                },
                onFatalError = { error ->
                    fatalMessage = when (error) {
                        is UnsatisfiedLinkError -> "SphereSLAM isn't available in this build."
                        else -> "SphereSLAM couldn't track this target. Recapture a textured wall patch."
                    }
                },
            )
            analyzer.start()
            cameraController.setImageAnalysisAnalyzer(executor, analyzer)

            onDispose {
                cameraController.clearImageAnalysisAnalyzer()
                executor.execute { analyzer.close() }
                executor.shutdown()
                glRenderer.clearPose()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setZOrderMediaOverlay(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setRenderer(glRenderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        onRelease = { view -> view.queueEvent { glRenderer.release() } },
        modifier = modifier.fillMaxSize(),
    )

    if (!referenceReady) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "Preparing wall tracker…",
                color = Color.White,
                modifier = Modifier
                    .padding(top = 32.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    } else if (isTrackingLost) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = strings.ar.reacquiringTarget,
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Button(
                    onClick = { resetCapture() },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Recapture Target")
                }
            }
        }
    }

    fatalMessage?.let { message ->
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = Color.White)
                Button(
                    onClick = { resetCapture() },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Recapture Target")
                }
            }
        }
    }
}

private fun bitmapToLuma(bitmap: Bitmap): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return ByteArray(pixels.size) { i ->
        val c = pixels[i]
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        ((77 * r + 150 * g + 29 * b) shr 8).toByte()
    }
}
