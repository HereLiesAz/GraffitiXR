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
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import com.hereliesaz.graffitixr.slam.SlamManager
import java.io.IOException
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
    var onSessionUpdated: ((Session, Frame) -> Unit)? = null // Hook for mapping screen

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    val slamManager = SlamManager()

    private val displayRotationHelper = com.hereliesaz.graffitixr.utils.DisplayRotationHelper(context)
    private var isSessionCreated = false
    private var anchor: Anchor? = null

    // Layer Management (Thread-Safe)
    // Map of Layer ID -> Renderer Data
    private val layerRenderers = ConcurrentHashMap<String, LayerRendererData>()

    private data class LayerRendererData(
        var textureId: Int = 0,
        var currentUri: String = "",
        var scale: Float = 1.0f,
        var rotationX: Float = 0.0f,
        var rotationY: Float = 0.0f,
        var rotationZ: Float = 0.0f,
        var opacity: Float = 1.0f,
        var offsetX: Float = 0.0f,
        var offsetY: Float = 0.0f,
        var aspectRatio: Float = 1.0f,
        var isVisible: Boolean = true
    )

    private val queuedTaps = java.util.concurrent.ArrayBlockingQueue<android.graphics.PointF>(16)
    private var captureNextFrame = false
    var showMiniMap = false
    var showGuide = true

    // Cache buffers to prevent GC stutter
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
        // Delete textures
        layerRenderers.values.forEach { 
            if (it.textureId != 0) {
                val tex = IntArray(1) { it.textureId }
                GLES20.glDeleteTextures(1, tex, 0)
            }
        }
        layerRenderers.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread(context)
        planeRenderer.createOnGlThread(context, "models/trigrid.png")
        pointCloudRenderer.createOnGlThread()
        simpleQuadRenderer.createOnGlThread()
        slamManager.initNative()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
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

        // Handle Taps
        handleTap(frame, camera)
        
        // Handle External Anchor Creation Request (from MappingScreen)
        anchorCreationPose?.value?.let { pose ->
            if (anchor == null) {
                anchor = session?.createAnchor(pose)
                onAnchorCreated?.invoke(anchor!!)
            }
            anchorCreationPose.value = null // Consume event
        }

        // --- Native Engine Feeds ---
        // Feed Image
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

        // Feed Depth
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

        // Render Background
        backgroundRenderer.draw(frame)

        val projmtx = FloatArray(16)
        camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
        val viewmtx = FloatArray(16)
        camera.getViewMatrix(viewmtx, 0)

        // Render Points
        val pointCloud = frame.acquirePointCloud()
        pointCloudRenderer.update(pointCloud)
        pointCloudRenderer.draw(viewmtx, projmtx)
        pointCloud.close()

        // Render Planes
        if (camera.trackingState == TrackingState.TRACKING) {
            val planes = session!!.getAllTrackables(Plane::class.java)
            if (planes.isNotEmpty()) {
                onPlanesDetected(true)
                planeRenderer.drawPlanes(planes, camera.displayOrientedPose, projmtx)
            } else {
                onPlanesDetected(false)
            }
        }

        // Render Layers (Images)
        if (anchor != null && anchor!!.trackingState == TrackingState.TRACKING) {
            val anchorMatrix = FloatArray(16)
            anchor!!.pose.toMatrix(anchorMatrix, 0)

            layerRenderers.values.forEach { layerData ->
                if (layerData.isVisible && layerData.textureId != 0) {
                    val modelMatrix = FloatArray(16)
                    Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, getLayerTransform(layerData), 0)
                    
                    // APPLY ASPECT RATIO HERE: Scale X by ratio
                    Matrix.scaleM(modelMatrix, 0, layerData.aspectRatio, 1.0f, 1.0f)

                    simpleQuadRenderer.draw(
                        viewmtx,
                        projmtx,
                        modelMatrix,
                        layerData.textureId,
                        layerData.opacity,
                        layerData.scale
                    )
                }
            }
        }

        // Call Native Draw (Invisible but running)
        slamManager.updateCamera(viewmtx, projmtx)
        slamManager.drawFrame()

        if (captureNextFrame) {
            captureNextFrame = false
            // Capture logic would go here (glReadPixels)
        }
        
        // Notify listener
        onSessionUpdated?.invoke(session!!, frame)
    }

    private fun getLayerTransform(layer: LayerRendererData): FloatArray {
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        // Order: Translate -> Rotate -> Scale
        Matrix.translateM(matrix, 0, layer.offsetX, layer.offsetY, 0.0f)
        Matrix.rotateM(matrix, 0, layer.rotationZ, 0f, 0f, 1f)
        Matrix.rotateM(matrix, 0, layer.rotationY, 0f, 1f, 0f)
        Matrix.rotateM(matrix, 0, layer.rotationX, 1f, 0f, 0f)
        // Base scale is applied in draw() separately or here. 
        // We'll let simpleQuadRenderer handle the uniform scale to avoid affecting aspect ratio logic too early.
        return matrix
    }

    fun updateLayers(layers: List<OverlayLayer>) {
        // 1. Remove layers that no longer exist
        val activeIds = layers.map { it.id }.toSet()
        layerRenderers.keys.removeIf { !activeIds.contains(it) }

        // 2. Update or Create layers
        for (layer in layers) {
            val data = layerRenderers.getOrPut(layer.id) { LayerRendererData() }

            // Check if URI changed (Expensive reload)
            if (data.currentUri != layer.uri.toString()) {
                data.currentUri = layer.uri.toString()
                data.textureId = loadTexture(context, layer.uri.toString())
            }

            // Update Transforms (Cheap)
            data.scale = layer.scale
            data.rotationX = layer.rotationX
            data.rotationY = layer.rotationY
            data.rotationZ = layer.rotationZ
            data.opacity = layer.opacity
            data.offsetX = layer.offset.x
            data.offsetY = layer.offset.y
            data.isVisible = layer.isVisible
            data.aspectRatio = layer.aspectRatio
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
        return session?.update()?.camera?.pose
    }
}
