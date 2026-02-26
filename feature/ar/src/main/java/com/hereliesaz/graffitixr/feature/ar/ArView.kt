package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.*
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                backgroundRenderer.createOnGlThread(context)
                planeRenderer.createOnGlThread(context)
                pointCloudRenderer.createOnGlThread(context)
                layerRenderer.createOnGlThread(context)
                slamManager.initialize()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                displayRotationHelper.onSurfaceChanged(width, height)
                GLES20.glViewport(0, 0, width, height)
                slamManager.onSurfaceChanged(width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                if (session == null) {
                    session = Session(context).apply {
                        val config = Config(this)
                        config.focusMode = Config.FocusMode.AUTO
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        this.configure(config)
                        this.setCameraTextureName(backgroundRenderer.textureId)
                    }
                    session?.resume()
                }

                val sessionObj = session ?: return
                displayRotationHelper.updateSessionIfNeeded(sessionObj)

                try {
                    val frame = sessionObj.update()
                    backgroundRenderer.draw(frame)

                    val camera = frame.camera
                    val viewMatrix = FloatArray(16)
                    val projMatrix = FloatArray(16)
                    camera.getViewMatrix(viewMatrix, 0)
                    camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

                    // 1. Render Detected Planes (Surface Polygons)
                    if (camera.trackingState == TrackingState.TRACKING) {
                        planeRenderer.drawPlanes(sessionObj, viewMatrix, projMatrix, camera.pose)
                    }

                    // 2. Render Point Cloud
                    val pointCloud = frame.acquirePointCloud()
                    pointCloudRenderer.update(pointCloud)
                    pointCloudRenderer.draw(viewMatrix, projMatrix)
                    pointCloud.release()

                    // 3. Render Splats & Update SLAM
                    slamManager.updateCamera(viewMatrix, projMatrix)
                    // Feed image for teleological tracking
                    frame.acquireCameraImage()?.let { image ->
                        viewModel.processTeleologicalFrame(image)
                        image.close()
                    }
                    slamManager.draw()

                    // 4. Render Active Graffiti Layer
                    activeLayer?.let { layer ->
                        layerRenderer.setBitmap(layer.bitmap)
                        // This projections logic can be refined to use an anchor
                        // For now, it draws on the identity plane
                        layerRenderer.draw(viewMatrix, projMatrix, FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }, layer)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            GLSurfaceView(it).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    )
}