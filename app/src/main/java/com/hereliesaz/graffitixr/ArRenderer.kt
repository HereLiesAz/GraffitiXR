package com.hereliesaz.graffitixr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.MiniMapRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import com.hereliesaz.graffitixr.slam.SlamManager
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.ImageUtils
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    val context: Context,
    private val onPlanesDetected: (Boolean) -> Unit,
    private val onFrameCaptured: (Bitmap) -> Unit,
    private val onAnchorCreated: () -> Unit,
    private val onProgressUpdated: (Float, Bitmap?) -> Unit,
    private val onTrackingFailure: (String?) -> Unit,
    private val onBoundsUpdated: (RectF) -> Unit
) : GLSurfaceView.Renderer {

    private val sessionLock = ReentrantLock()
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    private val miniMapRenderer = MiniMapRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)
    private val slamManager = SlamManager()

    @Volatile
    var session: Session? = null
    var isAnchorReplacementAllowed: Boolean = true // Default to true to allow initial placement
    var showMiniMap: Boolean = false
    var showGuide: Boolean = true

    // Texture for the camera feed
    private var backgroundTextureId = -1
    private var viewportWidth = 1
    private var viewportHeight = 1

    // Layers and Anchors
    private var layers: List<OverlayLayer> = emptyList()
    private val layerBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val layerUris = ConcurrentHashMap<String, Uri>()
    private var anchor: Anchor? = null
    private val queuedSingleTaps = ConcurrentLinkedQueue<FloatArray>()
    private val rendererScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Pre-allocated matrices to avoid GC churn
    private val anchorMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val layerMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val displayTransform = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // Identity

    fun updateLayers(newLayers: List<OverlayLayer>) {
        this.layers = newLayers
        val newLayerIds = newLayers.map { it.id }.toSet()

        // Trigger bitmap loading for new layers if needed
        newLayers.forEach { layer ->
            val cachedUri = layerUris[layer.id]
            // Reload if ID is new OR Uri has changed
            if (cachedUri == null || cachedUri != layer.uri) {
                 rendererScope.launch {
                     val bmp = ImageUtils.loadBitmapFromUri(context, layer.uri)
                     if (bmp != null) {
                         updateLayerBitmap(layer.id, bmp)
                         layerUris[layer.id] = layer.uri
                     }
                 }
            }
        }

        // Cleanup unused bitmaps
        val currentIds = layerBitmaps.keys.toList()
        currentIds.forEach { id ->
            if (id !in newLayerIds) {
                layerBitmaps.remove(id)
                layerUris.remove(id)
            }
        }
    }

    fun updateLayerBitmap(id: String, bitmap: Bitmap) {
        layerBitmaps[id] = bitmap
    }

    suspend fun generateFingerprint(bitmap: Bitmap): Fingerprint? = withContext(Dispatchers.Default) {
        if (!ensureOpenCVLoaded()) return@withContext null
        val mat = Mat()
        val grayMat = Mat()
        val descriptors = Mat()
        val keypoints = MatOfKeyPoint()
        val orb = ORB.create()

        try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
            orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

            if (descriptors.rows() > 0) {
                val kpsList = keypoints.toList()
                val descData = ByteArray(descriptors.rows() * descriptors.cols() * descriptors.channels())
                descriptors.get(0, 0, descData)

                return@withContext Fingerprint(
                    keypoints = kpsList,
                    descriptorsData = descData,
                    descriptorsRows = descriptors.rows(),
                    descriptorsCols = descriptors.cols(),
                    descriptorsType = descriptors.type()
                )
            }
            return@withContext null
        } catch (e: Exception) {
            Log.e("ArRenderer", "Fingerprint generation failed", e)
            return@withContext null
        } finally {
            mat.release()
            grayMat.release()
            descriptors.release()
            keypoints.release()
            orb.clear()
        }
    }

    fun setFlashlight(enabled: Boolean) {
        session?.let { s ->
            try {
                val config = s.config
                if (enabled) {
                    config.flashMode = Config.FlashMode.TORCH
                } else {
                    config.flashMode = Config.FlashMode.OFF
                }
                s.configure(config)
            } catch (e: Exception) {
                Log.e("ArRenderer", "Failed to set flashlight", e)
            }
        }
    }

    fun triggerCapture() {
        // Implement capture trigger
    }

    fun queueTap(x: Float, y: Float) {
        queuedSingleTaps.offer(floatArrayOf(x, y))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // 1. Initialize renderers
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        simpleQuadRenderer.createOnGlThread()
        miniMapRenderer.createOnGlThread(context)

        // 2. Use the texture ID from BackgroundRenderer for ARCore
        backgroundTextureId = backgroundRenderer.textureId
        
        // 3. Connect texture to session if ready
        session?.setCameraTextureName(backgroundTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) return

        displayRotationHelper.updateSessionIfNeeded(session!!)

        try {
            session!!.setCameraTextureName(backgroundTextureId)
            val frame = session!!.update()
            val camera = frame.camera

            // Handle Taps
            handleTaps(frame, camera)

            // Draw Background
            backgroundRenderer.draw(frame)

            // Get Projection Matrix
            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

            // Get View Matrix
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            // Visualize Point Cloud (Confidence Map)
            val pointCloud = frame.acquirePointCloud()
            pointCloudRenderer.update(pointCloud)
            pointCloudRenderer.draw(viewmtx, projmtx)
            
            // Draw MiniMap if enabled
            if (showMiniMap) {
                // Tactical View: Drone perspective of the point cloud
                miniMapRenderer.draw(pointCloud, camera.pose, viewportWidth, viewportHeight, 0)
            }

            pointCloud.release()

            // Detect Planes
            if (hasTrackingPlane()) {
                onPlanesDetected(true)
                planeRenderer.drawPlanes(
                    session!!.getAllTrackables(Plane::class.java),
                    viewmtx,
                    projmtx
                )
            } else {
                onPlanesDetected(false)
            }

            // Draw Layers
            anchor?.let {
                if (it.trackingState == TrackingState.TRACKING) {
                    it.pose.toMatrix(anchorMatrix, 0)

                    layers.forEach { layer ->
                        if (layer.isVisible) {
                            val bitmap = layerBitmaps[layer.id]
                            if (bitmap != null) {
                                // Calculate Model Matrix (Anchor * Layer Transform)
                                Matrix.setIdentityM(layerMatrix, 0)

                                // Apply Layer Transforms (T * R * S)
                                Matrix.translateM(layerMatrix, 0, layer.offset.x, layer.offset.y, 0f)
                                Matrix.rotateM(layerMatrix, 0, layer.rotationX, 1f, 0f, 0f)
                                Matrix.rotateM(layerMatrix, 0, layer.rotationY, 0f, 1f, 0f)
                                Matrix.rotateM(layerMatrix, 0, layer.rotationZ, 0f, 0f, 1f)
                                Matrix.scaleM(layerMatrix, 0, layer.scale, layer.scale, 1f)

                                Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, layerMatrix, 0)

                                // MVP Matrix
                                Matrix.multiplyMM(modelViewMatrix, 0, viewmtx, 0, modelMatrix, 0)
                                Matrix.multiplyMM(mvpMatrix, 0, projmtx, 0, modelViewMatrix, 0)

                                simpleQuadRenderer.draw(
                                    mvpMatrix,
                                    modelViewMatrix,
                                    bitmap,
                                    layer.opacity,
                                    layer.brightness,
                                    layer.colorBalanceR,
                                    layer.colorBalanceG,
                                    layer.colorBalanceB,
                                    -1, // Depth texture
                                    backgroundTextureId,
                                    viewportWidth.toFloat(),
                                    viewportHeight.toFloat(),
                                    floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f), // Identity
                                    layer.blendMode
                                )
                            }
                        }
                    }
                }
            }

        } catch (e: SessionPausedException) {
             Log.w("ArRenderer", "Session paused during update")
        } catch (t: Throwable) {
            Log.e("ArRenderer", "Exception on the OpenGL thread", t)
        }
    }

    private fun handleTaps(frame: com.google.ar.core.Frame, camera: com.google.ar.core.Camera) {
        val tap = queuedSingleTaps.poll() ?: return
        if (camera.trackingState != TrackingState.TRACKING) return

        val hitResultList = frame.hitTest(tap[0], tap[1])
        for (hit in hitResultList) {
            val trackable = hit.trackable
            if ((trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) ||
                (trackable is com.google.ar.core.Point && trackable.orientationMode == com.google.ar.core.Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)
            ) {
                if (isAnchorReplacementAllowed || anchor == null) {
                    anchor?.detach()
                    anchor = hit.createAnchor()
                    onAnchorCreated()
                    break // Only create one anchor
                }
            }
        }
    }

    private fun hasTrackingPlane(): Boolean {
        session?.getAllTrackables(Plane::class.java)?.forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING) {
                return true
            }
        }
        return false
    }

    fun onResume(activity: android.app.Activity) {
        displayRotationHelper.onResume()

        // Check for camera permission before creating session
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w("ArRenderer", "Camera permission not granted. Deferring AR Session creation.")
            return
        }

        try {
            if (session == null) {
                // Check if ARCore is installed/updated
                when (com.google.ar.core.ArCoreApk.getInstance().requestInstall(activity, true)) {
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        return // ARCore will pause activity to install
                    }
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED -> {
                        // Create the session
                        session = Session(context)
                        val config = Config(session)
                        config.focusMode = Config.FocusMode.AUTO
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        session!!.configure(config)
                        
                        // Set texture name if renderer is already created
                        if (backgroundTextureId != -1) {
                            session!!.setCameraTextureName(backgroundTextureId)
                        }
                    }
                }
            }
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e("ArRenderer", "Camera not available. Please restart the app.", e)
        } catch (e: Exception) {
            Log.e("ArRenderer", "Failed to create AR Session", e)
        }
    }

    fun onPause() {
        displayRotationHelper.onPause()
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
        rendererScope.cancel()
    }
}
