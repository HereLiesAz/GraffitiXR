package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager

/**
 * A unified screen composable that combines the background AR renderer
 * and the UI overlay. Useful for standalone Activities like MappingActivity.
 */
@Composable
fun MappingScreen(
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    onBackClick: () -> Unit,
    onScanComplete: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MappingBackground(
            slamManager = slamManager,
            projectRepository = projectRepository,
            onRendererCreated = { /* Lifecycle managed by Activity/View */ }
        )

        MappingUi(
            onBackClick = onBackClick,
            onScanComplete = onScanComplete
        )
    }
}

@Composable
fun MappingBackground(
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val context = LocalContext.current
    val renderer = remember(slamManager) { ArRenderer(slamManager) }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                onRendererCreated(renderer)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun MappingUi(
    onBackClick: () -> Unit,
    onScanComplete: () -> Unit
) {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onScanComplete) {
                Icon(Icons.Default.Check, contentDescription = "Complete Scan")
            }
        },
        topBar = {
            // Minimal top bar overlay
            FloatingActionButton(
                onClick = onBackClick,
                modifier = Modifier.padding(top = 16.dp, start = 16.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        },
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.padding(padding))
    }
}