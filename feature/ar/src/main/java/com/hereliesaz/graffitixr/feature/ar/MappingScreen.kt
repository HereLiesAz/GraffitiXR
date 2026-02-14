package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager

@Composable
fun MappingBackground(
    slamManager: SlamManager,
    projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository,
    onRendererCreated: (ArRenderer) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)

                    val r = ArRenderer(ctx, slamManager, projectRepository)
                    onRendererCreated(r)
                    setRenderer(r)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    r.glSurfaceView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun MappingUi(
    onScanComplete: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(text = "Mapping in progress...", color = Color.White)
        }

        Button(
            onClick = {
                onScanComplete()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Text("Finish Scan")
        }
    }
}