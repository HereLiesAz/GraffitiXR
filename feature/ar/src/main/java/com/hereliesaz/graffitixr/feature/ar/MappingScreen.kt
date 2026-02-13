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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun MappingScreen(
    onBackClick: () -> Unit,
    onScanComplete: () -> Unit,
    viewModel: ArViewModel = hiltViewModel(),
    slamManager: SlamManager
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var renderer by remember { mutableStateOf<ArRenderer?>(null) }

    LaunchedEffect(Unit) {
        while(true) {
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)

                    val r = ArRenderer(ctx, slamManager)
                    renderer = r
                    setRenderer(r)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    r.glSurfaceView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(text = "Mapping in progress...", color = androidx.compose.ui.graphics.Color.White)
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
