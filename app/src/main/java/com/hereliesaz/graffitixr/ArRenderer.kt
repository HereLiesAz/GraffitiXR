package com.hereliesaz.graffitixr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.BlendMode
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import com.hereliesaz.graffitixr.slam.SlamManager
import org.opencv.android.Utils as OpenCVUtils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    val context: Context,
    val onPlanesDetected: (Boolean) -> Unit,
    val onFrameCaptured: (Bitmap) -> Unit,
    val onProgressUpdated: (Float, Bitmap?) -> Unit,
    val onTrackingFailure: (String?) -> Unit,
    val onBoundsUpdated: (android.graphics.RectF) -> Unit,
    val anchorCreationPose: MutableState<Pose?>? = null 
) : GLSurfaceView.Renderer {

    var session: Session? = null
    var onAnchorCreated: ((Anchor) -> Unit)? = null
    var onSessionUpdated: ((Session, Frame) -> Unit)? = null 

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    val slamManager = SlamManager()

    private val displayRotationHelper = com.hereliesaz.graffitixr.utils.DisplayRotationHelper(context)
    private var isSessionCreated = false
    private var anchor: Anchor? = null
    private var lastKnownPose: Pose? = null

    // Layer Management (Thread-Safe)
    private val layerRenderers = ConcurrentHashMap<String, LayerRendererData>()

    private data class LayerRendererData(
        var textureId: Int = 0,
        var currentUri: String = "",
        var scale: Float = 1.0f,
        var rotationX: Float = 0.0f,
        var rotationY: Float = 0.0f,
        var rotationZ: Float = 0.0f,
        var opacity: Float = 1.0f,
        var brightness: Float = 0.0f,
        var colorBalanceR: Float = 1.0f,
        var colorBalanceG: Float = 1.0f,
        var colorBalanceB: Float = 1.0f,
        var offsetX: Float = 0.0f,
        var offsetY: Float = 0.0f,
        var aspectRatio: Float = 1.0f,
        var isVisible: Boolean = true,
        var blendMode: BlendMode = BlendMode.SrcOver
    )

    private val queuedTaps = java.util.concurrent.ArrayBlockingQueue<android.graphics.PointF>(16)
    private var captureNextFrame = false
    var showMiniMap = false
    var showGuide = true

    private var viewportWidth = 0
    private var viewportHeight = 0

    private var cachedYBuffer: ByteArray? = null
    private var cachedDepthBuffer: ByteArray? = null
    private var lastDepthUpdateTime = 0L

    fun onResume(activity: Activity) {
        if (session == null) {
            var message: String? = null
            try {
                if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    return
                }
                session = Session(context).apply {
                    val config = Config(this)
                    config.focusMode = Config.FocusMode.AUTO
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    configure(config)
                }
                isSessionCreated = true
            } catch (e: Exception) {
                message = "Failed to create AR session: ${e.message}"
            }

            if (message != null) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
            if (backgroundRenderer.textureId != -1) {
                session?.setCameraTextureName(backgroundRenderer.textureId)
            }
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(context, "Camera not available", Toast.LENGTH_LONG).show()
            session = null
            return
        }
        displayRotationHelper.onResume()
        slamManager.initNative()
    }

    fun onPause() {
        session?.pause()
        displayRotationHelper.onPause()
        slamManager.destroyNative()
    }

    fun cleanup() {
        session?.close()
        session = null
        layerRenderers.values.forEach { layer ->
            if (layer.textureId != 0) {
                val tex = IntArray(1) { layer.textureId }
                GLES20.glDeleteTextures(1, tex, 0)
            }
        }
        layerRenderers.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread()
        session?.setCameraTextureName(backgroundRenderer.textureId)
        planeRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        simpleQuadRenderer.createOnGlThread()
        slamManager.initNative()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) return
        val frame = try {
            session!!.update()
        } catch (e: Exception) {
            return
        }

        displayRotationHelper.updateSessionIfNeeded(session!!)
        val camera = frame.camera
        lastKnownPose = camera.pose

        handleTap(frame, camera)
        
        anchorCreationPose?.value?.let { pose ->
            if (anchor == null) {
                anchor = session?.createAnchor(pose)
                onAnchorCreated?.invoke(anchor!!)
            }
            anchorCreationPose.value = null 
        }

        try {
            val image = frame.acquireCameraImage()
            if (image.format == android.graphics.ImageFormat.YUV_420_888) {
                val width = image.width
                val height = image.height
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val buffer = plane.buffer
                val pixelCount = width * height

                if (cachedYBuffer == null || cachedYBuffer!!.size != pixelCount) {
                    cachedYBuffer = ByteArray(pixelCount)
                }

                if (rowStride == width) {
                    buffer.get(cachedYBuffer!!)
                } else {
                    for (row in 0 until height) {
                        buffer.position(row * rowStride)
                        buffer.get(cachedYBuffer!!, row * width, width)
                    }
                }
                slamManager.updateCameraImage(cachedYBuffer!!, width, height, frame.timestamp)
            }
            image.close()
        } catch (e: Exception) { }

        if (camera.trackingState == TrackingState.TRACKING) {
            val now = System.currentTimeMillis()
            if (now - lastDepthUpdateTime > 66) {
                try {
                    val depthImage = frame.acquireDepthImage16Bits()
                    val depthBuffer = depthImage.planes[0].buffer
                    val requiredSize = depthBuffer.remaining()
                    if (cachedDepthBuffer == null || cachedDepthBuffer!!.size != requiredSize) {
                        cachedDepthBuffer = ByteArray(requiredSize)
                    }
                    depthBuffer.get(cachedDepthBuffer!!)
                    slamManager.feedDepth(cachedDepthBuffer!!, depthImage.width, depthImage.height)
                    lastDepthUpdateTime = now
                    depthImage.close()
                } catch (e: Exception) { }
            }
        }

        backgroundRenderer.draw(frame)

        val projmtx = FloatArray(16)
        camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
        val viewmtx = FloatArray(16)
        camera.getViewMatrix(viewmtx, 0)

        val pointCloud = frame.acquirePointCloud()
        pointCloudRenderer.update(pointCloud)
        pointCloudRenderer.draw(viewmtx, projmtx)
        pointCloud.close()

        if (camera.trackingState == TrackingState.TRACKING) {
            val planes = session!!.getAllTrackables(Plane::class.java)
            if (planes.isNotEmpty()) {
                onPlanesDetected(true)
                planeRenderer.drawPlanes(planes, viewmtx, projmtx)
            } else {
                onPlanesDetected(false)
            }
        }

        if (anchor != null && anchor!!.trackingState == TrackingState.TRACKING) {
            val anchorMatrix = FloatArray(16)
            anchor!!.pose.toMatrix(anchorMatrix, 0)

            layerRenderers.values.forEach { layerData ->
                if (layerData.isVisible && layerData.textureId != 0) {
                    val modelMatrix = FloatArray(16)
                    Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, getLayerTransform(layerData), 0)
                    
                    // ASPECT RATIO FIX: Scale X by ratio
                    Matrix.scaleM(modelMatrix, 0, layerData.aspectRatio, 1.0f, 1.0f)

                    simpleQuadRenderer.draw(
                        viewmtx,
                        projmtx,
                        modelMatrix,
                        layerData.textureId,
                        layerData.opacity,
                        layerData.brightness,
                        layerData.colorBalanceR,
                        layerData.colorBalanceG,
                        layerData.colorBalanceB,
                        layerData.blendMode
                    )
                }
            }
        }

        slamManager.updateCamera(viewmtx, projmtx)
        slamManager.drawFrame()

        if (captureNextFrame) {
            captureNextFrame = false
            captureBitmap()
        }
        
        onSessionUpdated?.invoke(session!!, frame)
    }

    private fun captureBitmap() {
        try {
            val width = viewportWidth
            val height = viewportHeight
            val buffer = ByteBuffer.allocateDirect(width * height * 4)
            buffer.order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val matrix = android.graphics.Matrix()
            matrix.preScale(1.0f, -1.0f)
            val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
            bitmap.recycle()

            onFrameCaptured(flippedBitmap)
        } catch (e: Exception) {
            Log.e("ArRenderer", "Failed to capture frame", e)
        }
    }

    private fun getLayerTransform(layer: LayerRendererData): FloatArray {
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, layer.offsetX, layer.offsetY, 0.0f)
        Matrix.rotateM(matrix, 0, layer.rotationZ, 0f, 0f, 1f)
        Matrix.rotateM(matrix, 0, layer.rotationY, 0f, 1f, 0f)
        Matrix.rotateM(matrix, 0, layer.rotationX, 1f, 0f, 0f)
        return matrix
    }

    fun updateLayers(layers: List<OverlayLayer>) {
        val activeIds = layers.map { it.id }.toSet()
        layerRenderers.keys.removeIf { !activeIds.contains(it) }

        for (layer in layers) {
            val data = layerRenderers.getOrPut(layer.id) { LayerRendererData() }

            if (data.currentUri != layer.uri.toString()) {
                data.currentUri = layer.uri.toString()
                data.textureId = loadTexture(context, layer.uri.toString())
            }

            data.scale = layer.scale
            data.rotationX = layer.rotationX
            data.rotationY = layer.rotationY
            data.rotationZ = layer.rotationZ
            data.opacity = layer.opacity
            data.brightness = layer.brightness
            data.colorBalanceR = layer.colorBalanceR
            data.colorBalanceG = layer.colorBalanceG
            data.colorBalanceB = layer.colorBalanceB
            data.offsetX = layer.offset.x
            data.offsetY = layer.offset.y
            data.isVisible = layer.isVisible
            data.aspectRatio = layer.aspectRatio
            data.blendMode = layer.blendMode
        }
    }

    private fun loadTexture(context: Context, uriString: String): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        try {
            val uri = android.net.Uri.parse(uriString)
            val stream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream?.close()

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            bitmap.recycle()
        } catch (e: Exception) {
            Log.e("ArRenderer", "Error loading texture", e)
        }
        return textureId
    }

    fun queueTap(x: Float, y: Float) {
        queuedTaps.offer(android.graphics.PointF(x, y))
    }

    private fun handleTap(frame: Frame, camera: com.google.ar.core.Camera) {
        val tap = queuedTaps.poll() ?: return
        if (camera.trackingState == TrackingState.TRACKING) {
            val hitResult = frame.hitTest(tap.x, tap.y).firstOrNull { 
                val trackable = it.trackable
                (trackable is Plane && trackable.isPoseInPolygon(it.hitPose))
            }

            if (hitResult != null) {
                if (anchor != null) {
                    anchor?.detach()
                }
                anchor = hitResult.createAnchor()
                onAnchorCreated?.invoke(anchor!!)
            }
        }
    }

    fun triggerCapture() {
        captureNextFrame = true
    }
    
    fun getLatestPose(): Pose? {
        return lastKnownPose
    }

    fun setFlashlight(on: Boolean) {
        val session = session ?: return
        try {
            val config = session.config
            config.flashMode = if (on) Config.FlashMode.TORCH else Config.FlashMode.OFF
            session.configure(config)
        } catch (e: Exception) {
            Log.e("ArRenderer", "Failed to set flashlight: ${e.message}")
        }
    }

    fun generateFingerprint(bitmap: Bitmap): Fingerprint? {
        val mat = Mat()
        OpenCVUtils.bitmapToMat(bitmap, mat)

        val orb = ORB.create()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()

        orb.detectAndCompute(mat, Mat(), keypoints, descriptors)

        if (descriptors.empty()) {
            mat.release()
            keypoints.release()
            descriptors.release()
            return null
        }

        val descriptorsData = ByteArray(descriptors.rows() * descriptors.cols() * descriptors.elemSize().toInt())
        descriptors.get(0, 0, descriptorsData)

        val fingerprint = Fingerprint(
            keypoints.toList(),
            descriptorsData,
            descriptors.rows(),
            descriptors.cols(),
            descriptors.type()
        )

        mat.release()
        keypoints.release()
        descriptors.release()

        return fingerprint
    }
}
