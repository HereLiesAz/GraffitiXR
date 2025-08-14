package com.hereliesaz.MuralOverlay

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.MuralOverlay.rendering.BackgroundRenderer
import com.hereliesaz.MuralOverlay.rendering.ObjectRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.content.Context

class MuralRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val assets: AssetManager = context.assets
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var muralObject: ObjectRenderer
    private var session: Session? = null
    private val anchors = mutableListOf<Anchor>()
    private var currentState = MuralState()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer.createOnGlThread(assets)
        muralObject = ObjectRenderer(assets, "shaders/mural_object.vert", "shaders/mural_object.frag", null)
        muralObject.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        session?.let {
            val frame = it.update()
            backgroundRenderer.draw(frame)
            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                for (anchor in anchors) {
                    muralObject.draw(camera, anchor, currentState)
                }
            }
        }
    }

    fun setSession(session: Session) {
        this.session = session
    }

    fun handleTap(event: MotionEvent, frame: Frame) {
        if (frame.camera.trackingState == TrackingState.TRACKING) {
            for (hit in frame.hitTest(event)) {
                val trackable = hit.trackable
                if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                    anchors.add(hit.createAnchor())
                    break
                }
            }
        }
    }

    fun updateState(state: MuralState) {
        this.currentState = state
        if (state.imageUri != null) {
            try {
                val bitmap = context.contentResolver.openInputStream(state.imageUri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                if (bitmap != null) {
                    muralObject.updateTexture(bitmap)
                }
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}
