package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.ar.core.Session
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun MappingScreen(
    onMapSaved: (String) -> Unit,
    onExit: () -> Unit,
    onRendererCreated: (ArRenderer) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("graffiti_settings", Context.MODE_PRIVATE) }
    val isRightHanded = remember { prefs.getBoolean("is_right_handed", true) }

    var glSurfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val isMappingState = remember { mutableStateOf(true) }
    val isMapping by isMappingState

    // Initialize Renderer
    val arRenderer = remember {
        ArRenderer(context).also {
            onRendererCreated(it)
        }
    }

    val slamManager = arRenderer.slamManager
    val mappingQuality by slamManager.mappingQuality.collectAsState(initial = 0f)

    // Lifecycle Management
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
            glSurfaceView?.onPause()
            arRenderer.onPause(lifecycleOwner)
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
            onExit()
        })

        azRailItem(id = "rescan", text = "Rescan", info = "Clear Map", onClick = {
            slamManager.clearMap()
        })

        background(weight = 0) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                // Explicitly typed 'ctx' to fix inference error
                factory = { ctx: Context ->
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
                    navController = navController,
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
                                        val mapPath = File(context.filesDir, "$mapId.map").absolutePath

                                        scope.launch {
                                            val success = withContext(Dispatchers.IO) {
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