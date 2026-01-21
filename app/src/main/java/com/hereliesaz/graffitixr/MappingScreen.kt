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
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.graffitixr.slam.SlamManager
import kotlinx.coroutines.launch
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

@Composable
fun MappingScreen(
    onMapSaved: (String) -> Unit, // Callback when we get a Cloud ID
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("graffiti_settings", android.content.Context.MODE_PRIVATE) }
    val isRightHanded = remember { prefs.getBoolean("is_right_handed", true) }

    // The Manager (The Brain)
    val slamManager = remember { SlamManager() }

    // UI State
    val mappingQuality by slamManager.mappingQuality.collectAsState()
    val isHosting by slamManager.isHosting.collectAsState()
    var isMapping by remember { mutableStateOf(true) }

    // Renderer (The Eyes)
    val arRenderer = remember {
        ArRenderer(
            context = context,
            onPlanesDetected = {},
            onFrameCaptured = {},
            onAnchorCreated = {},
            onProgressUpdated = { _, _ -> },
            onTrackingFailure = {},
            onBoundsUpdated = {}
        ).apply {
            // ACTIVATE TACTICAL MODE
            showMiniMap = true
            showGuide = false
        }
    }

    // Lifecycle Management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> arRenderer.onResume(context as Activity)
                Lifecycle.Event.ON_PAUSE -> arRenderer.onPause()
                Lifecycle.Event.ON_DESTROY -> arRenderer.cleanup()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arRenderer.cleanup()
        }
    }

    // The Mapping Loop: Poll ARCore for "How good is this map?"
    LaunchedEffect(isMapping) {
        while (isMapping) {
            val session = arRenderer.session
            if (session != null) {
                // Ensure we are tracking before asking for quality
                if (session.update().camera.trackingState == TrackingState.TRACKING) {
                    val cameraPose = session.update().camera.pose
                    slamManager.updateFeatureMapQuality(session, cameraPose)
                }
            }
            kotlinx.coroutines.delay(500) // Don't spam the API
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. The AR View (World + MiniMap)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(arRenderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            }
        )

        // 2. Navigation
        AzNavRail(
            navController = null,
            currentDestination = "surveyor",
            isLandscape = false
        ) {
            azSettings(
                displayAppNameInHeader = true,
                dockingSide = if (isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
            )
            azRailItem(id = "back", text = "Abort", onClick = onExit)
        }

        // 3. The HUD (Neural Scan UI)
        if (isMapping) {
            // Map float quality to Enum
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
                    if (session != null) {
                        // The user is happy with the map.
                        // Create an anchor exactly where the device is NOW.
                        val cameraPose = session.update().camera.pose
                        // We place the anchor slightly in front (0.5m) to ensure stability
                        val forwardOffset = Pose.makeTranslation(0f, 0f, -0.5f)
                        val anchorPose = cameraPose.compose(forwardOffset)

                        val anchor = session.createAnchor(anchorPose)

                        // Initiate the Upload Ritual
                        scope.launch {
                            slamManager.hostAnchor(
                                session = session,
                                anchor = anchor,
                                onSuccess = { cloudId ->
                                    // Success. We have the ID.
                                    Toast.makeText(context, "Cloud Anchor Hosted!", Toast.LENGTH_SHORT).show()
                                    onMapSaved(cloudId)
                                },
                                onError = { error ->
                                    Toast.makeText(context, "Hosting Failed: $error", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }
                },
                onExit = onExit
            )
        }
    }
}
