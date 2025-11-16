package com.hereliesaz.graffitixr

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun ARScreen(arCoreManager: ARCoreManager) {
    val renderer = remember {
        ARCoreRenderer(arCoreManager, arCoreManager.backgroundRenderer, arCoreManager.pointCloudRenderer)
    }

    AndroidView(
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
            }
        }
    )
}
