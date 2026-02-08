package com.hereliesaz.graffitixr.feature.ar

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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.AzDockingSide
import kotlinx.coroutines.launch
import com.google.ar.core.Session
import java.util.UUID

@Composable
fun MappingScreen(
    onMapSaved: (String) -> Unit,
    onExit: () -> Unit,
    onRendererCreated: (ArRenderer) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("graffiti_settings", android.content.Context.MODE_PRIVATE) }
    val isRightHanded = remember { prefs.getBoolean("is_right_handed", true) }

    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val isMappingState = remember { mutableStateOf(true) }
    var isMapping by isMappingState

    val arRenderer = remember {
        ArRenderer(context = context)
    }
    
    // Pass renderer out if requested
    LaunchedEffect(arRenderer) {
        onRendererCreated(arRenderer)
    }

    val slamManager = arRenderer.slamManager
    val mappingQuality by slamManager.mappingQuality.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    arRenderer.onResume(lifecycleOwner)
                    glSurfaceView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    arRenderer.onPause(lifecycleOwner)
                    glSurfaceView?.onPause()
                }
                Lifecycle.Event.ON_DESTROY -> arRenderer.cleanup()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arRenderer.onPause(lifecycleOwner)
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
            azRailItem(id = "back", text = "Abort", onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onExit()
            })

            background(weight = 0) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        GLSurfaceView(ctx).apply {
                            preserveEGLContextOnPause = true
                            setEGLContextClientVersion(3)
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
                                    isHosting = false, 
                                    onCaptureComplete = {
                                        val session = arRenderer.session
                                        if (session != null) {
                                            val mapId = UUID.randomUUID().toString()
                                            val mapPath = java.io.File(context.filesDir, "$mapId.map").absolutePath
                                            
                                            scope.launch {
                                                val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    slamManager.saveWorld(mapPath)
                                                }
                                                if (success) {
                                                    onMapSaved(mapId)
                                                } else {
                                                    Toast.makeText(context, "Failed to save local map.", Toast.LENGTH_LONG).show()
                                                }
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
