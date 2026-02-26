package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * Manages the ARCore Session lifecycle and the OpenGL rendering pipeline.
 * Hardware camera is managed via ARCore. No CameraX bindings should be active simultaneously.
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
    val lifecycleOwner = LocalLifecycleOwner.current

    val sessionState = remember { mutableStateOf<Session?>(null) }
    var isSessionResumed by remember { mutableStateOf(false) }

    val backgroundRenderer = remember { BackgroundRenderer() }
    val planeRenderer = remember { PlaneRenderer() }
    val pointCloudRenderer = remember { PointCloudRenderer() }
    val layerRenderer = remember { ProjectedImageRenderer() }
    val displayRotationHelper = remember { DisplayRotationHelper(context) }

    // Synchronize ARCore Session with Activity Lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    try {
                        if (sessionState.value == null) {
                            val session = Session(context)
                            val config = Config(session).apply {
                                focusMode = Config.FocusMode.AUTO
                                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                            }
                            session.configure(config)
                            sessionState.value = session
                        }
                        sessionState.value?.resume()
                        displayRotationHelper.onResume()
                        isSessionResumed = true
                    } catch (e: Exception) {
                        Log.e("ArView", "ARCore Session resume failed", e)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isSessionResumed = false
                    displayRotationHelper.onPause()
                    sessionState.value?.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    sessionState.value?.close()
                    sessionState.value = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val renderer = remember {
        object : GLSurfaceView.Renderer {
            private val viewMatrix = FloatArray(16)
            private val projMatrix = FloatArray(16)

            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

                backgroundRenderer.createOnGlThread(context)
                planeRenderer.createOnGlThread(context)
                pointCloudRenderer.createOnGlThread(context)
                layerRenderer.createOnGlThread(context)

                slamManager.resetGLState()
                slamManager.initialize()
                slamManager.setVisualizationMode(0)

                sessionState.value?.setCameraTextureName(backgroundRenderer.textureId)
                onRendererCreated(ArRenderer(slamManager))
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                displayRotationHelper.onSurfaceChanged(width, height)
                GLES30.glViewport(0, 0, width, height)
                slamManager.onSurfaceChanged(width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

                val session = sessionState.value ?: return
                if (!isSessionResumed) return

                session.setCameraTextureName(backgroundRenderer.textureId)
                displayRotationHelper.updateSessionIfNeeded(session)

                try {
                    val frame = session.update()
                    val camera = frame.camera

                    // 1. Draw camera feed
                    backgroundRenderer.draw(frame)

                    // 2. Extract Pose
                    camera.getViewMatrix(viewMatrix, 0)
                    camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

                    // 3. Render Polygons (Planes)
                    if (camera.trackingState == TrackingState.TRACKING) {
                        planeRenderer.drawPlanes(session, viewMatrix, projMatrix, camera.pose)

                        frame.acquirePointCloud().use { pointCloud ->
                            pointCloudRenderer.update(pointCloud)
                            pointCloudRenderer.draw(viewMatrix, projMatrix)
                        }
                    }

                    // 4. Update Native SLAM
                    slamManager.updateCamera(viewMatrix, projMatrix)

                    val lightEstimate = frame.lightEstimate
                    if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                        slamManager.updateLight(lightEstimate.pixelIntensity)
                    }

                    slamManager.draw()

                    // 5. Render Projected Artwork
                    activeLayer?.let { layer ->
                        layerRenderer.setBitmap(layer.bitmap)
                        val identity = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
                        layerRenderer.draw(viewMatrix, projMatrix, identity, layer)
                    }

                    // 6. Asynchronous CV Tasks
                    if (frame.timestamp % 15 == 0L) {
                        try {
                            frame.acquireCameraImage()?.use { image ->
                                val yBuffer = image.planes[0].buffer
                                val yData = ByteArray(yBuffer.remaining())
                                yBuffer.get(yData)
                                viewModel.processTeleologicalFrame(yData, image.width, image.height)
                            }
                        } catch (e: Exception) { }
                    }

                } catch (e: Exception) {
                    Log.e("ArView", "Draw frame failure", e)
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    )
}