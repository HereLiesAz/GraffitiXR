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
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TL, TR, BR, BL — a centered inset rectangle, matching [UnwarpScreen]'s expected point order. */
private val DEFAULT_UNWARP_POINTS = listOf(
    Offset(0.25f, 0.25f),
    Offset(0.75f, 0.25f),
    Offset(0.75f, 0.75f),
    Offset(0.25f, 0.75f),
)

/**
 * Drop-in fallback tracking + rendering for `EditorMode.OVERLAY` on ARCore-unavailable devices —
 * the piece that finally makes Phase 1's tracking math and Phase 2a/2b's camera math visible on
 * screen. Layer this over [CameraPreview] exactly the way `MainScreen.kt` layers AR mode's own
 * `GLSurfaceView` over its camera background: `setZOrderMediaOverlay(true)` +
 * `PixelFormat.TRANSLUCENT` so this draws on top while staying otherwise invisible.
 *
 * Self-contained on purpose — owns its own reference capture ("Capture Target" -> drag the
 * corners onto the shape via the same [UnwarpScreen] AR mode's own target capture uses -> the
 * rectified rectangle becomes the tracked reference), [BridgedHomographyTracker],
 * [HomographyTrackingAnalyzer], and [HomographyOverlayRenderer], attaching/detaching the CameraX
 * analyzer as this enters/leaves composition — so wiring it into `MainScreen.kt`'s existing
 * `EditorMode.OVERLAY` branch is a small, mechanical addition rather than a deep change to that
 * file or `ArViewModel`.
 *
 * The rectified bitmap's own aspect ratio becomes the tracked rectangle's half-extents — a
 * measurement of the marked shape in the RECTIFIED image's own pixel units (not the whole camera
 * frame), exact when the corners were marked square-on and increasingly approximate the more
 * obliquely they were marked (`PerspectiveProcessor.unwarpImage` sizes the rectified output from
 * each edge's longest visible length, not a true metric aspect — that needs the camera intrinsics
 * and a plane-normal decomposition, which nothing here computes).
 *
 * @param designBitmap the current design composite to texture the tracked quad with; null draws
 *   nothing (tracking still runs, so a design supplied later appears without re-tracking).
 * @param onPoseTracked observes each frame's tracking result — null means lost, which also
 *   drives this composable's own "Reacquiring target…" banner (ARCore's `TrackingState`
 *   equivalent). Optional — for a caller that additionally wants the raw pose stream.
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
    var rawCaptureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var unwarpPoints by remember { mutableStateOf(DEFAULT_UNWARP_POINTS) }
    var referenceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Set when a marked patch was rejected (too plain/blurry — HomographyTracker::setReference
    // needs >= 30 ORB features) so the "Capture Target" screen can say why instead of the user
    // just landing back there with no explanation. Cleared on the next capture attempt.
    var referenceRejected by remember { mutableStateOf(false) }

    if (referenceBitmap == null) {
        val scope = rememberCoroutineScope()
        val raw = rawCaptureBitmap
        if (raw == null) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (referenceRejected) {
                        Text(
                            text = "That patch didn't have enough detail to track — try a spot with more texture or pattern.",
                            color = Color.White,
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    Button(
                        onClick = {
                            referenceRejected = false
                            scope.launch {
                                rawCaptureBitmap = runCatching { cameraController.takePictureAsBitmap(context) }
                                    .getOrNull()
                            }
                        },
                        modifier = Modifier
                            .padding(bottom = 32.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                    ) {
                        Text("Capture Target")
                    }
                }
            }
        } else {
            val strings = rememberAppStrings()
            UnwarpScreen(
                bitmap = raw,
                points = unwarpPoints,
                onUpdatePoints = { unwarpPoints = it },
                onConfirm = { points ->
                    scope.launch(Dispatchers.Default) {
                        val pixelPoints = points.map { Offset(it.x * raw.width, it.y * raw.height) }
                        val unwarped = PerspectiveProcessor.unwarpImage(raw, pixelPoints)
                        withContext(Dispatchers.Main) {
                            if (unwarped != null) {
                                referenceBitmap = unwarped
                            } else {
                                rawCaptureBitmap = null
                            }
                        }
                    }
                },
                onCancel = {
                    rawCaptureBitmap = null
                    unwarpPoints = DEFAULT_UNWARP_POINTS
                },
                strings = strings,
            )
        }
        return
    }
    val reference = referenceBitmap!!
    val objectHalfW = 1f
    val objectHalfH = reference.height.toFloat() / reference.width.toFloat()

    val bridgedTracker = remember(context) { BridgedHomographyTracker(context) }
    val glRenderer = remember(context) { HomographyOverlayRenderer(context) }
    // AR mode's own TrackingState equivalent — HomographyTrackingAnalyzer's callback runs on a
    // background executor, but Compose's Snapshot state supports writes from any thread and
    // schedules recomposition correctly, so this needs no dispatcher hop.
    var isTrackingLost by remember { mutableStateOf(false) }

    DisposableEffect(bridgedTracker) {
        bridgedTracker.start()
        onDispose { bridgedTracker.stop() }
    }

    LaunchedEffect(bridgedTracker, reference, objectHalfW, objectHalfH) {
        // setReference runs synchronous native ORB detection over a full photo — off the Main
        // dispatcher LaunchedEffect otherwise runs on, so it doesn't jank composition. Its result
        // was previously discarded: a rejected reference (too plain/blurry — under
        // HomographyTracker's minimum feature count) left the user reading "Reacquiring target…"
        // forever, with no control on screen that could get them back to recapture.
        val accepted = withContext(Dispatchers.Default) {
            bridgedTracker.setReference(reference, objectHalfW, objectHalfH)
        }
        if (accepted) {
            glRenderer.setExtent(objectHalfW, objectHalfH)
        } else {
            referenceRejected = true
            referenceBitmap = null
            rawCaptureBitmap = null
        }
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
                isTrackingLost = frame == null
                if (frame != null) {
                    glRenderer.updatePose(frame.pose.viewMatrix, frame.projMatrix, frame.frameAspect)
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

    // TrackingState.TRACKING == false, ARCore mode's own equivalent (see ArViewModel's
    // scan_hint_recover) — this fallback had no such signal at all before, silently drawing
    // nothing with no explanation why.
    if (isTrackingLost) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = "Reacquiring target…",
                color = Color.White,
                modifier = Modifier
                    .padding(top = 32.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
