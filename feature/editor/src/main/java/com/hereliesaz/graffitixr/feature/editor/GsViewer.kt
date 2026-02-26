package com.hereliesaz.graffitixr.feature.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

// IMPORTANT: Replace this with your actual SlamManager import path!
// import com.hereliesaz.graffitixr.core.YOUR_ACTUAL_PACKAGE_HERE.SlamManager

@Composable
fun GsViewer(
    mapPath: String,
    slamManager: Any, // Change 'Any' to 'SlamManager' once imported correctly
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceView = remember { SurfaceView(context) }

    DisposableEffect(Unit) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Cast to your SlamManager class to access these methods
                // (slamManager as SlamManager).setSurface(holder.surface)
                // (slamManager as SlamManager).setVisualizationMode(2)

                // if (mapPath.isNotEmpty()) {
                //     (slamManager as SlamManager).loadWorld(mapPath)
                // }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // (slamManager as SlamManager).onSurfaceChanged(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // (slamManager as SlamManager).setSurface(null)
            }
        }

        surfaceView.holder.addCallback(callback)

        onDispose {
            surfaceView.holder.removeCallback(callback)
            // (slamManager as SlamManager).setSurface(null)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { surfaceView }
    )
}