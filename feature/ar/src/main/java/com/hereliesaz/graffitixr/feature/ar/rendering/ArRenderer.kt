package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.PixelCopy
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.util.ImageProcessingUtils
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.DisplayRotationHelper
import com.hereliesaz.graffitixr.feature.ar.util.MeshGenerator
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.*
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val projectRepository: ProjectRepository
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

    // Teleological Loop State
    private var lastCorrectionTime = 0L
    private var isCorrecting = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            // FIX: Reset native GL state because we have a new EGLContext.
            // This prevents the "Ghost Renderer" crash where the singleton engine
            // tries to use VBOs from a destroyed activity.
            slamManager.resetGLState()

            backgroundRenderer.createOnGlThread(context)
            slamManager.initialize()
            // INDICATE splatted areas using Fog of War removal
            slamManager.setVisualizationMode(3) // FOG_OF_WAR
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

        val session = this.session ?: return
        displayRotationHelper.updateSessionIfNeeded(session)

        try {
            session.setCameraTextureName(backgroundRenderer.textureId)
            val frame = session.update()
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

            // NEW: Light Estimation
            frame.lightEstimate?.let { estimate ->
                val intensity = estimate.pixelIntensity
                val colorCorrection = FloatArray(4)
                estimate.getColorCorrection(colorCorrection, 0)
                slamManager.updateLight(intensity, colorCorrection[0], colorCorrection[1], colorCorrection[2])
            }

            // --- TELEOLOGICAL LOOP CLOSURE ---
            val now = System.currentTimeMillis()
            if (now - lastCorrectionTime > 2000 && !isCorrecting && camera.trackingState == TrackingState.TRACKING) {
                performTeleologicalCorrection(camera)
                lastCorrectionTime = now
            }

            // Handle Depth for Occlusion & Mapping
            val depthMode = session.config.depthMode
            if (depthMode == Config.DepthMode.AUTOMATIC || depthMode == Config.DepthMode.RAW_DEPTH_ONLY) {
                try {
                    val depthImage = if (depthMode == Config.DepthMode.RAW_DEPTH_ONLY) {
                        frame.acquireRawDepthImage16Bits()
                    } else {
                        frame.acquireDepthImage16Bits()
                    }
                    val cameraImage = frame.acquireCameraImage() // For color mapping

                    if (depthImage != null && cameraImage != null) {
                        val depthBuffer = depthImage.planes[0].buffer
                        val colorBuffer = cameraImage.planes[0].buffer

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

                        // NEW: Generate Mesh for precise surface projection
                        if (depthMode == Config.DepthMode.RAW_DEPTH_ONLY) {
                            val meshVertices = MeshGenerator.generateMesh(
                                depthBuffer = depthBuffer.asShortBuffer(),
                                width = depthImage.width,
                                height = depthImage.height,
                                intrinsics = camera.imageIntrinsics
                            )
                            slamManager.updateMesh(meshVertices)
                        }

                        depthImage.close()
                        cameraImage.close()
                    }
                } catch (e: Exception) {
                    // Depth or Camera image not available yet, ignore
                }
            }

            // Render Native Content (Splats, Map)
            if (camera.trackingState == TrackingState.TRACKING) {
                slamManager.draw()
            }

        } catch (e: com.google.ar.core.exceptions.SessionPausedException) {
            // Expected during app pause, ignore gracefully
        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun performTeleologicalCorrection(camera: com.google.ar.core.Camera) {
        val view = glSurfaceView ?: return

        isCorrecting = true

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(view, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                rendererScope.launch {
                    try {
                        val project = projectRepository.currentProject.value
                        val fingerprint = project?.fingerprint

                        if (fingerprint != null) {
                            val intrinsics = camera.imageIntrinsics
                            val intrinsicsArray = floatArrayOf(
                                intrinsics.focalLength[0], intrinsics.focalLength[1],
                                intrinsics.principalPoint[0], intrinsics.principalPoint[1]
                            )

                            val tTargetToCamera = ImageProcessingUtils.solvePnP(bitmap, fingerprint, intrinsicsArray)

                            if (tTargetToCamera != null) {
                                val tWorldToCamera = Mat(4, 4, CvType.CV_64F)
                                for (i in 0..3) {
                                    for (j in 0..3) {
                                        tWorldToCamera.put(i, j, cameraPoseMatrix[j * 4 + i].toDouble())
                                    }
                                }

                                val tCameraToWorld = tWorldToCamera.inv()
                                val tTargetToWorld = Mat()
                                org.opencv.core.Core.gemm(tCameraToWorld, tTargetToCamera, 1.0, Mat(), 0.0, tTargetToWorld)

                                val correctionArray = FloatArray(16)
                                for(i in 0..3) {
                                    for(j in 0..3) {
                                        // Transpose back to column-major for GLM/ARCore
                                        correctionArray[j * 4 + i] = tTargetToWorld.get(i, j)[0].toFloat()
                                    }
                                }

                                slamManager.alignMap(correctionArray)

                                tWorldToCamera.release()
                                tCameraToWorld.release()
                                tTargetToWorld.release()
                                tTargetToCamera.release()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Teleological correction failed", e)
                    } finally {
                        isCorrecting = false
                        bitmap.recycle()
                    }
                }
            } else {
                isCorrecting = false
                bitmap.recycle()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
        glSurfaceView?.onResume()
        if (session == null) {
            try {
                session = Session(context)
                val config = Config(session)

                // Prefer RAW_DEPTH (LiDAR) if available, fallback to AUTOMATIC
                config.depthMode = if (session!!.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    Config.DepthMode.RAW_DEPTH_ONLY
                } else {
                    Config.DepthMode.AUTOMATIC
                }

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
        glSurfaceView?.onPause()
    }

    fun setFlashlight(on: Boolean) {}
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