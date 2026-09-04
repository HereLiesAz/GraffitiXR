// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/HomographyFallbackOverlay.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.feature.ar.rendering.HomographyOverlayRenderer
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drop-in fallback tracking + rendering for `EditorMode.OVERLAY` on ARCore-unavailable devices —
 * the piece that finally makes Phase 1's tracking math and Phase 2a/2b's camera math visible on
 * screen. Layer this over [CameraPreview] exactly the way `MainScreen.kt` layers AR mode's own
 * `GLSurfaceView` over its camera background: `setZOrderMediaOverlay(true)` +
 * `PixelFormat.TRANSLUCENT` so this draws on top while staying otherwise invisible.
 *
 * Self-contained on purpose — owns its [BridgedHomographyTracker], [HomographyTrackingAnalyzer],
 * and [HomographyOverlayRenderer] internally, attaching/detaching the CameraX analyzer as this
 * enters/leaves composition — so wiring it into `MainScreen.kt`'s existing `EditorMode.OVERLAY`
 * branch is a small, mechanical addition rather than a deep change to that file or `ArViewModel`.
 *
 * No-ops (renders nothing) until [referenceBitmap] is non-null — the caller is responsible for
 * getting one, e.g. via a `TargetCreationFlow`-style capture reused for this purpose.
 *
 * @param referenceBitmap the traced/painted shape's reference photo — see
 *   `HomographyArTracker.setReference`'s doc for what [objectHalfW]/[objectHalfH] must match.
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
    referenceBitmap: Bitmap?,
    objectHalfW: Float,
    objectHalfH: Float,
    designBitmap: Bitmap?,
    onPoseTracked: (HomographyArTracker.HomographyPose?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (referenceBitmap == null) return

    val context = LocalContext.current
    val bridgedTracker = remember(context) { BridgedHomographyTracker(context) }
    val glRenderer = remember(context) { HomographyOverlayRenderer(context) }

    DisposableEffect(bridgedTracker) {
        bridgedTracker.start()
        onDispose { bridgedTracker.stop() }
    }

    LaunchedEffect(bridgedTracker, referenceBitmap, objectHalfW, objectHalfH) {
        // setReference runs synchronous native ORB detection over a full photo — off the Main
        // dispatcher LaunchedEffect otherwise runs on, so it doesn't jank composition.
        withContext(Dispatchers.Default) {
            bridgedTracker.setReference(referenceBitmap, objectHalfW, objectHalfH)
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
