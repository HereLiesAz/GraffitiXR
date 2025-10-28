package com.hereliesaz.graffitixr

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ARScreen(arCoreManager: ARCoreManager) {
    AndroidView(
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(ARCoreRenderer(arCoreManager))
            }
        }
    )
}
