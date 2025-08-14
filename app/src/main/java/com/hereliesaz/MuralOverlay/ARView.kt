package com.hereliesaz.MuralOverlay

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.Session

@Composable
fun ARView(
    modifier: Modifier = Modifier,
    state: MuralState
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MuralGLSurfaceView(context).apply {
                val renderer = MuralRenderer(context)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                renderer.setSession(session)
            }
        },
        update = { glSurfaceView ->
            // Pass state changes to the renderer
            val muralRenderer = glSurfaceView.renderer as MuralRenderer
            muralRenderer.updateState(state)
        }
    )
}

class MuralGLSurfaceView(context: Context) : GLSurfaceView(context) {
    val session: Session = Session(context)
    lateinit var renderer: MuralRenderer

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val frame = session.update()
            renderer.handleTap(event, frame)
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        session.pause()
    }

    override fun onResume() {
        super.onResume()
        session.resume()
    }

    override fun setRenderer(renderer: Renderer) {
        this.renderer = renderer as MuralRenderer
        super.setRenderer(renderer)
    }
}
