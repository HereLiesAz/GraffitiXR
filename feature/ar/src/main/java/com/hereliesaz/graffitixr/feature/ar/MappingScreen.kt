package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager

@Composable
fun MappingScreen(
    onBackClick: () -> Unit,
    onScanComplete: () -> Unit,
    viewModel: ArViewModel = hiltViewModel(),
    slamManager: SlamManager // FIXED: Added parameter
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // We create the renderer ONCE.
    // Ideally this should be remembered, but AndroidView factory runs once usually.
    // To be safe with Recomposition, we can remember it,
    // BUT GLSurfaceView inside AndroidView manages its own lifecycle mostly.

    // Fix: We need to reference the renderer to attach lifecycle.
    var renderer by remember { mutableStateOf<ArRenderer?>(null) }

    // State for UI overlay
    var pointCount by remember { mutableIntStateOf(0) }
    var quality by remember { mutableStateOf("INIT") }

    // Polling for stats (Quick dirty way, ideal is Flow)
    LaunchedEffect(Unit) {
        while(true) {
            pointCount = slamManager.getPointCount()
            quality = slamManager.mappingQuality
            kotlinx.coroutines.delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)

                    // Create Renderer with injected SlamManager
                    val r = ArRenderer(ctx, slamManager)
                    renderer = r
                    setRenderer(r)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    
                    // Link the view to the renderer for lifecycle synchronization
                    r.glSurfaceView = this

                    // Attach lifecycle
                    lifecycleOwner.lifecycle.addObserver(r)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(text = "Points: $pointCount", color = androidx.compose.ui.graphics.Color.White)
            Text(text = "Quality: $quality", color = androidx.compose.ui.graphics.Color.Green) // FIXED: Now resolves
        }

        Button(
            onClick = {
                // Save and exit
                // In reality, you'd show a dialog input for filename
                val path = context.filesDir.absolutePath + "/scan_${System.currentTimeMillis()}.gxrm"
                if (slamManager.saveWorld(path)) {
                    Toast.makeText(context, "Map Saved!", Toast.LENGTH_SHORT).show()
                    onScanComplete()
                } else {
                    Toast.makeText(context, "Save Failed", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Text("Finish Scan")
        }
    }
}