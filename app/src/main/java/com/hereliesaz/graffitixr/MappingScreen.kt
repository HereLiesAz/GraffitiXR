package com.hereliesaz.graffitixr

import android.app.Activity
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.graffitixr.slam.SlamManager
import kotlinx.coroutines.launch
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.concurrent.atomic.AtomicReference

@Composable
fun MappingScreen(
    onMapSaved: (String) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("graffiti_settings", android.content.Context.MODE_PRIVATE) }
    val isRightHanded = remember { prefs.getBoolean("is_right_handed", true) }

    val slamManager = remember { SlamManager() }

    // Thread-safe state to hold the latest camera pose from GL thread
    val latestCameraPose = remember { AtomicReference<Pose?>(null) }

    // Capture GLSurfaceView to manage lifecycle
    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }

    // State for safe UI access
    val latestCameraPose = remember { mutableStateOf<Pose?>(null) }

    // UI State
    val mappingQuality by slamManager.mappingQuality.collectAsState()
    val isHosting by slamManager.isHosting.collectAsState()
    val isMappingState = remember { mutableStateOf(true) }
    var isMapping by isMappingState

    val arRenderer = remember {
        var lastUpdateTime = 0L
        ArRenderer(
            context = context,
            onPlanesDetected = {},
            onFrameCaptured = {},
            onAnchorCreated = {},
            onProgressUpdated = { _, _ -> },
            onTrackingFailure = {},
            onBoundsUpdated = {}
        ).apply {
            showMiniMap = true
            showGuide = false
            onSessionUpdated = { session, frame ->
                // Always update the latest pose if tracking
                if (frame.camera.trackingState == TrackingState.TRACKING) {
                    latestCameraPose.value = frame.camera.pose
                }

                if (isMappingState.value) {
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > 500) {
                        if (frame.camera.trackingState == TrackingState.TRACKING) {
                            val cameraPose = frame.camera.pose
                            slamManager.updateFeatureMapQuality(session, cameraPose)
                            lastUpdateTime = now
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    arRenderer.onResume(context as Activity)
                    glSurfaceView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    arRenderer.onPause()
                    glSurfaceView?.onPause()
                }
                Lifecycle.Event.ON_DESTROY -> arRenderer.cleanup()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arRenderer.onPause()
            glSurfaceView?.onPause()
            arRenderer.cleanup()
        }
    }

    val navController = rememberNavController()

    AzHostActivityLayout(
        modifier = Modifier.fillMaxSize(),
        navController = navController
    ) {
            azConfig(
                displayAppName = true,
                dockingSide = if (isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
            )
            azRailItem(id = "back", text = "Abort", onClick = onExit)

            background(weight = 0) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        GLSurfaceView(ctx).apply {
                            preserveEGLContextOnPause = true
                            setEGLContextClientVersion(3) // FIXED: Must be 3 for Splat Shaders
                            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                            setRenderer(arRenderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                            glSurfaceView = this
                        }
                    }
                )
            }

            onscreen(alignment = Alignment.Center) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AzNavHost(
                        startDestination = "mapping_content"
                    ) {
                        composable("mapping_content") {
                            if (isMapping) {
                                val qualityEnum = when {
                                    mappingQuality < 0.5f -> Session.FeatureMapQuality.INSUFFICIENT
                                    mappingQuality < 0.8f -> Session.FeatureMapQuality.SUFFICIENT
                                    else -> Session.FeatureMapQuality.GOOD
                                }

                                PhotoSphereCreationScreen(
                                    isRightHanded = isRightHanded,
                                    currentQuality = qualityEnum,
                                    isHosting = isHosting,
                                    onCaptureComplete = {
                                        val session = arRenderer.session
                                        val cameraPose = latestCameraPose.value
                                        if (session != null && cameraPose != null) {
                                            // The user is happy with the map.
                                            // Create an anchor exactly where the device is NOW.

                                            // We place the anchor slightly in front (0.5m) to ensure stability
                                            val forwardOffset = Pose.makeTranslation(0f, 0f, -0.5f)
                                            val anchorPose = cameraPose.compose(forwardOffset)
                                            val anchor = session.createAnchor(anchorPose)

                                            scope.launch {
                                                slamManager.hostAnchor(
                                                    session = session,
                                                    anchor = anchor,
                                                    onSuccess = { cloudId ->
                                                        Toast.makeText(context, "Cloud Anchor Hosted!", Toast.LENGTH_SHORT).show()
                                                        onMapSaved(cloudId)
                                                    },
                                                    onError = { error ->
                                                        Toast.makeText(context, "Hosting Failed: $error", Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            }
                                        } else {
                                            Toast.makeText(context, "Tracking not ready", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onExit = onExit
                                )
                            }
                        }
                    }
                }
            }
    }
}
