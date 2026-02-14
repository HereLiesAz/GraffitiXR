package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.feature.ar.DisplayRotationHelper
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.*
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager
) : GLSurfaceView.Renderer {

    private val rendererScope = CoroutineScope(Dispatchers.Default + Job())
    var glSurfaceView: GLSurfaceView? = null
    var showPointCloud: Boolean = true

    private var session: Session? = null
    private val displayRotationHelper = DisplayRotationHelper(context)
    private val backgroundRenderer = BackgroundRenderer()
    
    // Matrix buffers
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val cameraPoseMatrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            backgroundRenderer.createOnGlThread(context)
            slamManager.initialize()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read asset file", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) return

        displayRotationHelper.updateSessionIfNeeded(session!!)

        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)
            val frame = session!!.update()
            val camera = frame.camera

            // Draw Background (Camera Feed)
            backgroundRenderer.draw(frame)

            // Update SLAM Manager with Camera Matrices
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            
            // Get Camera Pose for SLAM
            val pose = camera.pose
            pose.toMatrix(cameraPoseMatrix, 0)

            slamManager.updateCamera(viewMatrix, projectionMatrix)

            // Handle Depth for Occlusion & Mapping
            if (session!!.config.depthMode == Config.DepthMode.AUTOMATIC) {
                try {
                    val depthImage = frame.acquireDepthImage16Bits()
                    val cameraImage = frame.acquireCameraImage() // For color mapping

                    if (depthImage != null && cameraImage != null) {
                        // Pass data to Native Engine
                        // Note: Native engine must handle locking/unlocking or copying
                        // For now, we assume direct buffer access is safe if strictly scoped
                        val depthBuffer = depthImage.planes[0].buffer
                        val colorBuffer = cameraImage.planes[0].buffer
                        
                        // Calculate vertical FOV
                        val intrinsics = camera.imageIntrinsics
                        val fovY = (2.0 * Math.atan(intrinsics.principalPoint[1] / intrinsics.focalLength[1].toDouble())).toFloat()

                        slamManager.feedDepthData(
                            depthBuffer = depthBuffer,
                            colorBuffer = colorBuffer,
                            width = depthImage.width,
                            height = depthImage.height,
                            depthStride = depthImage.planes[0].rowStride,
                            colorStride = cameraImage.planes[0].rowStride,
                            poseMtx = cameraPoseMatrix,
                            fov = fovY
                        )
                    }

                    depthImage?.close()
                    cameraImage?.close()
                } catch (e: Exception) {
                    // Depth or Camera image not available yet, ignore
                }
            }

            // Render Native Content (Splats, Map)
            if (camera.trackingState == TrackingState.TRACKING) {
                slamManager.draw()
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
        if (session == null) {
            try {
                session = Session(context)
                val config = Config(session)
                config.depthMode = Config.DepthMode.AUTOMATIC
                config.focusMode = Config.FocusMode.AUTO
                config.updateMode = Config.UpdateMode.BLOCKING
                session!!.configure(config)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
            }
        }

        try {
            session?.resume()
            displayRotationHelper.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
        }
    }

    fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
        displayRotationHelper.onPause()
        session?.pause()
    }

    fun setFlashlight(on: Boolean) {
        val config = session?.config ?: return
        if (on) {
            // ARCore doesn't directly expose flashlight in Config for all versions, 
            // but we can try setting it via shared camera or config if supported.
            // For now, leaving as stub since standard Config doesn't have simple toggle.
            // Standard approach is via Camera2 API interoperability.
        }
    }

    fun setLayer(layer: Layer?) {}
    fun handleTap(x: Float, y: Float) {}

    fun cleanup() {
        rendererScope.cancel()
        session?.close()
        session = null
    }

    companion object {
        private const val TAG = "ArRenderer"
    }
}
