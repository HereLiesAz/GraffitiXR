package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.design.rendering.ProjectedImageRenderer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The AR Viewport implementation.
 * Manages the ARCore Session, the OpenGL rendering lifecycle, and the integration
 * with the native MobileGS engine.
 */
@Composable
fun ArView(
    viewModel: ArViewModel,
    uiState: ArUiState,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    activeLayer: Layer?,
    onRendererCreated: (ArRenderer) -> Unit,
    hasCameraPermission: Boolean
) {
    if (!hasCameraPermission) return
    val context = LocalContext.current

    val renderer = remember {
        object : GLSurfaceView.Renderer {
            private var session: Session? = null
            private val backgroundRenderer = BackgroundRenderer()
            private val planeRenderer = PlaneRenderer()
            private val pointCloudRenderer = PointCloudRenderer()
            private val layerRenderer = ProjectedImageRenderer()
            private val displayRotationHelper = DisplayRotationHelper(context)

            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

                // Initialize GL components
                backgroundRenderer.createOnGlThread(context)
                planeRenderer.createOnGlThread(context)
                pointCloudRenderer.createOnGlThread(context)
                layerRenderer.createOnGlThread(context)

                // Initialize native engine components
                slamManager.resetGLState()
                slamManager.initialize()

                // Set to AR Scanning mode (Vulkan backend managed internally where possible)
                slamManager.setVisualizationMode(0)

                // Notify parent
                onRendererCreated(ArRenderer(slamManager))
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                displayRotationHelper.onSurfaceChanged(width, height)
                GLES20.glViewport(0, 0, width, height)
                slamManager.onSurfaceChanged(width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                // Clear screen
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                // Initialize session if not present
                if (session == null) {
                    try {
                        session = Session(context).apply {
                            val config = Config(this)
                            config.focusMode = Config.FocusMode.AUTO
                            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                            this.configure(config)
                            this.setCameraTextureName(backgroundRenderer.textureId)
                        }
                        session?.resume()
                    } catch (e: Exception) {
                        Log.e("ArView", "Failed to create ARCore session", e)
                        return
                    }
                }

                val sessionObj = session ?: return
                displayRotationHelper.updateSessionIfNeeded(sessionObj)

                try {
                    val frame = sessionObj.update()

                    // 1. Draw camera feed
                    backgroundRenderer.draw(frame)

                    val camera = frame.camera
                    val viewMatrix = FloatArray(16)
                    val projMatrix = FloatArray(16)
                    camera.getViewMatrix(viewMatrix, 0)
                    camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

                    // 2. Visualise Surface Planes
                    if (camera.trackingState == TrackingState.TRACKING) {
                        planeRenderer.drawPlanes(sessionObj, viewMatrix, projMatrix, camera.pose)
                    }

                    // 3. Visualise Point Cloud (Raw ARCore features)
                    frame.acquirePointCloud().use { pointCloud ->
                        pointCloudRenderer.update(pointCloud)
                        pointCloudRenderer.draw(viewMatrix, projMatrix)
                    }

                    // 4. Update Native SLAM Engine (Voxel Map)
                    slamManager.updateCamera(viewMatrix, projMatrix)

                    // Feed current image for teleological (OpenCV) correction
                    try {
                        frame.acquireCameraImage().use { image ->
                            viewModel.processTeleologicalFrame(image)
                        }
                    } catch (e: Exception) {
                        // Image might be unavailable this frame
                    }

                    // Feed light estimation to native splat renderer
                    val lightEstimate = frame.lightEstimate
                    if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                        slamManager.updateLight(lightEstimate.pixelIntensity)
                    }

                    // Render the native splats
                    slamManager.draw()

                    // 5. Draw Active Projection Layer
                    activeLayer?.let { layer ->
                        layerRenderer.setBitmap(layer.bitmap)

                        // Identity matrix for the 3D quad placement
                        val anchorMatrix = FloatArray(16)
                        android.opengl.Matrix.setIdentityM(anchorMatrix, 0)

                        // Render onto the digital wall
                        layerRenderer.draw(viewMatrix, projMatrix, anchorMatrix, layer)
                    }

                } catch (e: Exception) {
                    Log.e("ArView", "Render error", e)
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    )
}