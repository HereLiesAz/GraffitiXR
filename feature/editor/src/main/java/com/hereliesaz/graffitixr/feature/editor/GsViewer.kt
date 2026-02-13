package com.hereliesaz.graffitixr.feature.editor

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.feature.editor.rendering.GsViewerRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlin.math.sqrt

/**
 * A Composable that renders the 3D SLAM map using the native MobileGS engine.
 * It uses [GsViewerRenderer] to handle OpenGL rendering and [SlamManager] for data processing.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun GsViewer(
    mapPath: String,
    slamManager: SlamManager, // FIXED: Added
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize Renderer with the map file path AND shared engine
    val renderer = remember(mapPath) {
        GsViewerRenderer(context, mapPath, slamManager)
    }

    DisposableEffect(renderer) {
        onDispose {
            renderer.cleanup()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                // Touch Handling for Orbit/Zoom
                setOnTouchListener { _, event ->
                    handleTouch(event, renderer)
                    true
                }
            }
        }
    )
}

// Basic Multi-touch Handler
private var lastX = 0f
private var lastY = 0f
private var lastDist = 0f
private var mode = 0 // 0=NONE, 1=DRAG, 2=ZOOM

private fun handleTouch(event: MotionEvent, renderer: GsViewerRenderer) {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            mode = 1 // DRAG
            lastX = event.x
            lastY = event.y
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
            mode = 2 // ZOOM
            lastDist = spacing(event)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            mode = 0
        }
        MotionEvent.ACTION_MOVE -> {
            if (mode == 1) {
                // Orbit
                val dx = event.x - lastX
                val dy = event.y - lastY
                renderer.onTouchDrag(dx, dy)
                lastX = event.x
                lastY = event.y
            } else if (mode == 2) {
                // Zoom
                val newDist = spacing(event)
                if (newDist > 10f) {
                    val scale = newDist / lastDist
                    renderer.onTouchScale(scale)
                    lastDist = newDist
                }
            }
        }
    }
}

private fun spacing(event: MotionEvent): Float {
    val x = event.getX(0) - event.getX(1)
    val y = event.getY(0) - event.getY(1)
    return sqrt(x * x + y * y)
}