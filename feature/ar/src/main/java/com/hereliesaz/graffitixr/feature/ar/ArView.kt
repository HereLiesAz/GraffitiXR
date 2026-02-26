package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLES20
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
 * Replaces CameraX with ARCore's native camera feed for high-precision tracking.
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

    // 1. Manage Session Lifecycle explicitly
    val sessionState = remember { mutableStateOf<Session?>(null) }

    // 2. Initialize Renderers once
    val backgroundRenderer = remember { BackgroundRenderer() }
    val planeRenderer = remember { PlaneRenderer() }
    val pointCloudRenderer = remember { PointCloudRenderer() }
    val layerRenderer = remember { ProjectedImageRenderer() }
    val displayRotationHelper = remember { DisplayRotationHelper(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (sessionState.value == null) {
                        try {
                            val session = Session(context)
                            val config = Config(session).apply {
                                focusMode = Config.FocusMode.AUTO
                                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                            }
                            session.configure(config)
                            sessionState.value = session
                        } catch (e: Exception) {
                            Log.e("ArView", "ARCore Session creation failed", e)
                        }
                    }
                    sessionState.value?.resume()
                    displayRotationHelper.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
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
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

                // Initialize renderers on the GL thread
                backgroundRenderer.createOnGlThread(context)
                planeRenderer.createOnGlThread(context)
                pointCloudRenderer.createOnGlThread(context)
                layerRenderer.createOnGlThread(context)

                // Initialize native engine components
                slamManager.resetGLState()
                slamManager.initialize()
                slamManager.setVisualizationMode(0) // AR Mode

                // Connect ARCore to the background texture
                sessionState.value?.setCameraTextureName(backgroundRenderer.textureId)

                onRendererCreated(ArRenderer(slamManager))
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                displayRotationHelper.onSurfaceChanged(width, height)
                GLES20.glViewport(0, 0, width, height)
                slamManager.onSurfaceChanged(width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                val session = sessionState.value ?: return

                // Texture must be set before session update
                session.setCameraTextureName(backgroundRenderer.textureId)
                displayRotationHelper.updateSessionIfNeeded(session)

                try {
                    val frame = session.update()
                    val camera = frame.camera

                    // 1. DRAW CAMERA FEED
                    backgroundRenderer.draw(frame)

                    // 2. EXTRACT CAMERA MATRICES
                    camera.getViewMatrix(viewMatrix, 0)
                    camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

                    // 3. RENDER SURFACE POLYGONS
                    if (camera.trackingState == TrackingState.TRACKING) {
                        planeRenderer.drawPlanes(session, viewMatrix, projMatrix, camera.pose)
                    }

                    // 4. RENDER POINT CLOUD
                    frame.acquirePointCloud().use { pointCloud ->
                        pointCloudRenderer.update(pointCloud)
                        pointCloudRenderer.draw(viewMatrix, projMatrix)
                    }

                    // 5. UPDATE NATIVE SLAM
                    slamManager.updateCamera(viewMatrix, projMatrix)

                    // Feed light estimation
                    val lightEstimate = frame.lightEstimate
                    if (lightEstimate.state == com.google.ar.core.LightEstimate.State.VALID) {
                        slamManager.updateLight(lightEstimate.pixelIntensity)
                    }

                    // Native Splat Map Render
                    slamManager.draw()

                    // 6. RENDER ACTIVE PROJECTED LAYER
                    activeLayer?.let { layer ->
                        layerRenderer.setBitmap(layer.bitmap)
                        val identity = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
                        layerRenderer.draw(viewMatrix, projMatrix, identity, layer)
                    }

                    // 7. BACKGROUND TELEOLOGICAL TASKS
                    if (frame.timestamp % 10 == 0L) {
                        try {
                            frame.acquireCameraImage()?.use { image ->
                                viewModel.processTeleologicalFrame(image)
                            }
                        } catch (e: Exception) { }
                    }

                } catch (e: Exception) {
                    Log.e("ArView", "Render failure", e)
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