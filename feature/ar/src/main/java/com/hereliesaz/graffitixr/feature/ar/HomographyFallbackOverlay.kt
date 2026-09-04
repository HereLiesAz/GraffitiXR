// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/HomographyFallbackOverlay.kt
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drop-in fallback tracking + rendering for `EditorMode.OVERLAY` on ARCore-unavailable devices —
 * the piece that finally makes Phase 1's tracking math and Phase 2a/2b's camera math visible on
 * screen. Layer this over [CameraPreview] exactly the way `MainScreen.kt` layers AR mode's own
 * `GLSurfaceView` over its camera background: `setZOrderMediaOverlay(true)` +
 * `PixelFormat.TRANSLUCENT` so this draws on top while staying otherwise invisible.
 *
 * Self-contained on purpose — owns its own reference capture (a "Capture Target" button, shown
 * until the artist taps it), [BridgedHomographyTracker], [HomographyTrackingAnalyzer], and
 * [HomographyOverlayRenderer], attaching/detaching the CameraX analyzer as this enters/leaves
 * composition — so wiring it into `MainScreen.kt`'s existing `EditorMode.OVERLAY` branch is a
 * small, mechanical addition rather than a deep change to that file or `ArViewModel`.
 *
 * The captured reference photo's own full frame is treated as the tracked rectangle (half-width
 * fixed at 1.0, half-height at its aspect ratio) — a placeholder convention, not a measurement of
 * anything on the wall; a future pass that lets the artist mark the shape's actual corners (e.g.
 * reusing `TargetCreationFlow`'s `UnwarpScreen`, as AR mode's own target capture does) would
 * replace this, not the tracking underneath it.
 *
 * @param designBitmap the current design composite to texture the tracked quad with; null draws
 *   nothing (tracking still runs, so a design supplied later appears without re-tracking).
 * @param onPoseTracked observes each frame's tracking result — null means lost. Optional; mainly
 *   for a caller that wants to surface a "reacquiring…" indicator (ARCore's `TrackingState`
 *   equivalent) alongside this composable.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun HomographyFallbackOverlay(
    cameraController: LifecycleCameraController,
    designBitmap: Bitmap?,
    onPoseTracked: (HomographyArTracker.HomographyPose?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var referenceBitmap by remember { mutableStateOf<Bitmap?>(null) }

    if (referenceBitmap == null) {
        val scope = rememberCoroutineScope()
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Button(
                onClick = {
                    scope.launch {
                        referenceBitmap = runCatching { cameraController.takePictureAsBitmap(context) }
                            .getOrNull()
                    }
                },
                modifier = Modifier
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
            ) {
                Text("Capture Target")
            }
        }
        return
    }
    val reference = referenceBitmap!!
    val objectHalfW = 1f
    val objectHalfH = reference.height.toFloat() / reference.width.toFloat()

    val bridgedTracker = remember(context) { BridgedHomographyTracker(context) }
    val glRenderer = remember(context) { HomographyOverlayRenderer(context) }

    DisposableEffect(bridgedTracker) {
        bridgedTracker.start()
        onDispose { bridgedTracker.stop() }
    }

    LaunchedEffect(bridgedTracker, reference, objectHalfW, objectHalfH) {
        // setReference runs synchronous native ORB detection over a full photo — off the Main
        // dispatcher LaunchedEffect otherwise runs on, so it doesn't jank composition.
        withContext(Dispatchers.Default) {
            bridgedTracker.setReference(reference, objectHalfW, objectHalfH)
        }
        glRenderer.setExtent(objectHalfW, objectHalfH)
    }

    LaunchedEffect(glRenderer, designBitmap) {
        designBitmap?.let { glRenderer.updateDesignBitmap(it) }
    }

    // cameraController.cameraInfo is null until CameraPreview has actually bound the controller to
    // a lifecycle; the analyzer needs a real Camera2 id (for CameraIntrinsicsEstimator) that only
    // exists once that binding has happened, so this polls the same composition's recomposition
    // rather than assuming bind-order against CameraPreview.
    val cameraId by produceState<String?>(initialValue = null, cameraController.cameraInfo) {
        value = cameraController.cameraInfo?.let { Camera2CameraInfo.from(it).cameraId }
    }

    DisposableEffect(cameraController, bridgedTracker, cameraId) {
        val id = cameraId
        if (id == null) {
            onDispose {}
        } else {
            val executor = Executors.newSingleThreadExecutor()
            val analyzer = HomographyTrackingAnalyzer(context, id, bridgedTracker) { frame ->
                onPoseTracked(frame?.pose)
                if (frame != null) {
                    glRenderer.updatePose(frame.pose.viewMatrix, frame.projMatrix)
                } else {
                    glRenderer.clearPose()
                }
            }
            cameraController.setImageAnalysisAnalyzer(executor, analyzer)
            onDispose {
                cameraController.clearImageAnalysisAnalyzer()
                executor.shutdown()
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
}
