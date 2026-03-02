package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val onTrackingUpdate: (TrackingState, Int) -> Unit
) : GLSurfaceView.Renderer {

    // Made public to satisfy the ViewModel access requirements
    var session: Session? = null
    private val backgroundRenderer = BackgroundRenderer()
    private val pointCloudRenderer = FeaturePointRenderer()

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        pointCloudRenderer.createOnGlThread() // Context parameter removed to match signature
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            val frame = currentSession.update()
            val camera = frame.camera

            backgroundRenderer.draw(frame)

            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

                val persistentPoints = slamManager.getPersistedPoints()
                val pointCount = slamManager.nativeGetPersistedPointCount()

                if (persistentPoints != null && pointCount > 0) {
                    pointCloudRenderer.update(persistentPoints, pointCount)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                    onTrackingUpdate(camera.trackingState, pointCount)
                } else {
                    val arCloud = frame.acquirePointCloud()
                    onTrackingUpdate(camera.trackingState, arCloud.points.remaining() / 4)
                    pointCloudRenderer.update(arCloud)
                    pointCloudRenderer.draw(viewMatrix, projectionMatrix)
                    arCloud.release()
                }
            }
        } catch (e: Exception) {
            // Suppress teardown race conditions
        }
    }

    fun attachSession(session: Session) {
        this.session = session
    }
}
