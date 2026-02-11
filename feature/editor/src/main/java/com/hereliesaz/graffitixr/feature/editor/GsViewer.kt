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
import kotlin.math.sqrt

@SuppressLint("ClickableViewAccessibility")
@Composable
fun GsViewer(
    mapPath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize Renderer with the map file path
    val renderer = remember(mapPath) {
        GsViewerRenderer(context, mapPath)
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
// (In a real app, use ScaleGestureDetector and GestureDetector)
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