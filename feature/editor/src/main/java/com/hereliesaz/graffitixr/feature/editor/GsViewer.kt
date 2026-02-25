package com.hereliesaz.graffitixr.feature.editor

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.feature.editor.rendering.GsViewerRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlin.math.sqrt

class TouchState {
    var lastX = 0f
    var lastY = 0f
    var lastDist = 0f
    var mode = 0 // 0=NONE, 1=DRAG, 2=ZOOM
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun GsViewer(
    mapPath: String,
    slamManager: SlamManager,
    modifier: Modifier = Modifier,
    activeLayer: Layer? = null
) {
    val context = LocalContext.current

    val renderer = remember(mapPath) {
        GsViewerRenderer(context, mapPath, slamManager)
    }

    val touchState = remember { TouchState() }

    LaunchedEffect(activeLayer) {
        renderer.activeLayer = activeLayer
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

                setOnTouchListener { _, event ->
                    handleTouch(event, renderer, touchState)
                    true
                }
            }
        }
    )
}

private fun handleTouch(event: MotionEvent, renderer: GsViewerRenderer, state: TouchState) {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            state.mode = 1
            state.lastX = event.x
            state.lastY = event.y
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
            state.mode = 2
            state.lastDist = spacing(event)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
            state.mode = 0
        }
        MotionEvent.ACTION_MOVE -> {
            if (state.mode == 1) {
                val dx = event.x - state.lastX
                val dy = event.y - state.lastY
                renderer.onTouchDrag(dx, dy)
                state.lastX = event.x
                state.lastY = event.y
            } else if (state.mode == 2) {
                val newDist = spacing(event)
                if (newDist > 10f) {
                    val scale = newDist / state.lastDist
                    renderer.onTouchScale(scale)
                    state.lastDist = newDist
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