package com.hereliesaz.graffitixr

import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.composables.ConstraintBox

@Composable
fun ARScreen(arCoreManager: ARCoreManager, overlayImageUri: Uri?) {
    val context = LocalContext.current
    val renderer = remember { ARCoreRenderer(arCoreManager, context) }

    LaunchedEffect(overlayImageUri) {
        overlayImageUri?.let {
            renderer.updateTexture(it)
        }
    }
    ConstraintBox {
        AndroidView(
            factory = {
                GLSurfaceView(it).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                }
            }
        )
    }
}
