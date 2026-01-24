package com.hereliesaz.graffitixr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
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
    @Volatile
    var isSessionPaused = true
        private set
    var onSessionUpdated: ((Session, Frame) -> Unit)? = null
    var isAnchorReplacementAllowed: Boolean = true
    var showMiniMap: Boolean = false
    var showGuide: Boolean = true

    private var backgroundTextureId = -1
    private var viewportWidth = 1
    private var viewportHeight = 1

    private var layers: List<OverlayLayer> = emptyList()
    private val layerBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val layerUris = ConcurrentHashMap<String, Uri>()
    private var anchor: Anchor? = null
    private val queuedSingleTaps = ConcurrentLinkedQueue<FloatArray>()
    private val rendererScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val anchorMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val layerMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val displayTransform = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private var capturePending = false
    private var lastDepthUpdateTime = 0L

    fun updateLayers(newLayers: List<OverlayLayer>) {
        this.layers = newLayers
        val newLayerIds = newLayers.map { it.id }.toSet()

        newLayers.forEach { layer ->
            val cachedUri = layerUris[layer.id]
            if (cachedUri == null || cachedUri != layer.uri) {
                 rendererScope.launch {
                     val bmp = ImageUtils.loadBitmapFromUri(context, layer.uri)
                     if (bmp != null) {
                         val currentLayer = layers.find { it.id == layer.id }
                         if (currentLayer != null && currentLayer.uri == layer.uri) {
                             updateLayerBitmap(layer.id, bmp)
                             layerUris[layer.id] = layer.uri
                         }
                     }
                 }
            }
        }

        val currentIds = layerBitmaps.keys.toList()
        currentIds.forEach { id ->
            if (id !in newLayerIds) {
                val bitmap = layerBitmaps.remove(id)
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
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
                config.flashMode = if (enabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
                s.configure(config)
            } catch (e: Exception) {
                Log.e("ArRenderer", "Failed to set flashlight", e)
            }
        }
    }

    fun triggerCapture() {
        capturePending = true
    }

    fun queueTap(x: Float, y: Float) {
        queuedSingleTaps.offer(floatArrayOf(x, y))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        simpleQuadRenderer.createOnGlThread()
        miniMapRenderer.createOnGlThread(context)

        backgroundTextureId = backgroundRenderer.textureId
        session?.setCameraTextureName(backgroundTextureId)

        // Initialize Native Engine
        slamManager.initNative()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return
        displayRotationHelper.updateSessionIfNeeded(currentSession)

        try {
            currentSession.setCameraTextureName(backgroundTextureId)
            val frame = currentSession.update()
            onSessionUpdated?.invoke(currentSession, frame)
            val camera = frame.camera

            handleTaps(frame, camera)
            backgroundRenderer.draw(frame)

            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            // Feed Native Engine
            slamManager.updateCamera(viewmtx, projmtx)

            // Process Depth for MobileGS (Throttled to 10fps for performance)
            if (camera.trackingState == TrackingState.TRACKING) {
                val now = System.currentTimeMillis()
                if (now - lastDepthUpdateTime > 100) {
                    try {
                        val depthImage = frame.acquireDepthImage16Bits()
                        try {
                            val depthBuffer = depthImage.planes[0].buffer
                            val depthBytes = ByteArray(depthBuffer.remaining())
                            depthBuffer.get(depthBytes)
                            slamManager.feedDepth(depthBytes, depthImage.width, depthImage.height)
                            lastDepthUpdateTime = now
                        } finally {
                            depthImage.close()
                        }
                    } catch (e: Exception) {
                        // Depth not always available
                    }
                }
            }

            // Draw MobileGS Splats
            slamManager.drawFrame()

            val pointCloud = frame.acquirePointCloud()
            pointCloudRenderer.update(pointCloud)
            pointCloudRenderer.draw(viewmtx, projmtx)
            
            if (showMiniMap) {
                miniMapRenderer.draw(pointCloud, camera.pose, viewportWidth, viewportHeight, 0)
            }
            pointCloud.release()

            if (hasTrackingPlane()) {
                onPlanesDetected(true)
                planeRenderer.drawPlanes(currentSession.getAllTrackables(Plane::class.java), viewmtx, projmtx)
            } else {
                onPlanesDetected(false)
            }

            // Draw Standard Layers
            anchor?.let {
                if (it.trackingState == TrackingState.TRACKING) {
                    it.pose.toMatrix(anchorMatrix, 0)
                    layers.forEach { layer ->
                        if (layer.isVisible) {
                            val bitmap = layerBitmaps[layer.id]
                            if (bitmap != null) {
                                Matrix.setIdentityM(layerMatrix, 0)
                                Matrix.translateM(layerMatrix, 0, layer.offset.x, layer.offset.y, 0f)
                                Matrix.rotateM(layerMatrix, 0, layer.rotationX, 1f, 0f, 0f)
                                Matrix.rotateM(layerMatrix, 0, layer.rotationY, 0f, 1f, 0f)
                                Matrix.rotateM(layerMatrix, 0, layer.rotationZ, 0f, 0f, 1f)
                                Matrix.scaleM(layerMatrix, 0, layer.scale, layer.scale, 1f)

                                Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, layerMatrix, 0)
                                Matrix.multiplyMM(modelViewMatrix, 0, viewmtx, 0, modelMatrix, 0)
                                Matrix.multiplyMM(mvpMatrix, 0, projmtx, 0, modelViewMatrix, 0)

                                simpleQuadRenderer.draw(
                                    mvpMatrix, modelViewMatrix, bitmap, layer.opacity,
                                    layer.brightness, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB,
                                    -1, backgroundTextureId, viewportWidth.toFloat(), viewportHeight.toFloat(),
                                    displayTransform, layer.blendMode
                                )
                            }
                        }
                    }
                }
            }

            if (capturePending) {
                capturePending = false
                val bitmap = createBitmapFromGLSurface(0, 0, viewportWidth, viewportHeight)
                bitmap?.let { onFrameCaptured(it) }
            }

        } catch (e: SessionPausedException) {
             Log.w("ArRenderer", "Session paused during update")
        } catch (t: Throwable) {
            Log.e("ArRenderer", "Exception on the OpenGL thread", t)
        }
    }

    private fun createBitmapFromGLSurface(x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val bitmapBuffer = java.nio.IntBuffer.allocate(w * h)
        bitmapBuffer.position(0)
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bitmapBuffer)
        val bitmapSource = IntArray(w * h)
        val offset1 = bitmapBuffer.array()
        val offset2 = bitmapSource
        for (i in 0 until h) {
            val offset1Index = i * w
            val offset2Index = (h - i - 1) * w
            for (j in 0 until w) {
                val texturePixel = offset1[offset1Index + j]
                val blue = (texturePixel shr 16) and 0xff
                val red = (texturePixel shl 16) and 0x00ff0000
                val pixel = (texturePixel and -0xff0100) or red or blue
                offset2[offset2Index + j] = pixel
            }
        }
        return try {
            Bitmap.createBitmap(offset2, 0, w, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            null
        }
    }

    private fun handleTaps(frame: Frame, camera: com.google.ar.core.Camera) {
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
                    break
                }
            }
        }
    }

    private fun hasTrackingPlane(): Boolean {
        session?.getAllTrackables(Plane::class.java)?.forEach { plane ->
            if (plane.trackingState == TrackingState.TRACKING) return true
        }
        return false
    }

    fun onResume(activity: android.app.Activity) {
        displayRotationHelper.onResume()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        try {
            if (session == null) {
                when (com.google.ar.core.ArCoreApk.getInstance().requestInstall(activity, true)) {
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return
                    com.google.ar.core.ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(context)
                        val config = Config(session)
                        config.focusMode = Config.FocusMode.AUTO
                        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        // ENABLE DEPTH
                        if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            config.depthMode = Config.DepthMode.AUTOMATIC
                        }
                        session!!.configure(config)
                        if (backgroundTextureId != -1) session!!.setCameraTextureName(backgroundTextureId)
                    }
                }
            }
            session?.resume()
            isSessionPaused = false
        } catch (e: CameraNotAvailableException) {
            Log.e("ArRenderer", "Camera not available", e)
        } catch (e: Exception) {
            Log.e("ArRenderer", "Failed to create AR Session", e)
        }
    }

    fun onPause() {
        displayRotationHelper.onPause()
        session?.pause()
        isSessionPaused = true
    }

    fun cleanup() {
        slamManager.destroyNative()
        session?.close()
        session = null
        rendererScope.cancel()
        layerBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        layerBitmaps.clear()
        layerUris.clear()
    }
}
