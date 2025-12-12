package com.hereliesaz.graffitixr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.YuvToRgbConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val context: Context,
    private val onPlanesDetected: (Boolean) -> Unit,
    private val onFrameCaptured: (Bitmap) -> Unit,
    private val onAnchorCreated: () -> Unit,
    private val onProgressUpdated: (Float, Bitmap?) -> Unit,
    private val onTrackingFailure: (String?) -> Unit
) : GLSurfaceView.Renderer {

    // Helper for main thread posting to avoid JNI crashes
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasTracking = false

    // AR Session
    var session: Session? = null
    private val displayRotationHelper = DisplayRotationHelper(context)

    // Renderers
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()

    // Utils
    private val yuvToRgbConverter = YuvToRgbConverter(context)

    // Flags & State
    @Volatile private var captureNextFrame = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    @Volatile var overlayBitmap: Bitmap? = null
    @Volatile var arImagePose: FloatArray? = null
    @Volatile var arState: ArState = ArState.SEARCHING

    // Transforms
    var opacity: Float = 1.0f
    var brightness: Float = 0f
    var scale: Float = 1.0f
    var rotationX: Float = 0f
    var rotationY: Float = 0f
    var rotationZ: Float = 0f
    var colorBalanceR: Float = 1.0f
    var colorBalanceG: Float = 1.0f
    var colorBalanceB: Float = 1.0f

    private val tapQueue = ConcurrentLinkedQueue<Pair<Float, Float>>()

    private var originalDescriptors: Mat? = null
    private var originalKeypointCount: Int = 0
    private var lastAnalysisTime = 0L
    private val ANALYSIS_INTERVAL_MS = 2000L

    fun setFingerprint(fingerprint: Fingerprint) {
        this.originalDescriptors = fingerprint.descriptors
        this.originalKeypointCount = fingerprint.keypoints.size
    }

    private fun analyzeFrameAsync(frame: Frame) {
        try {
            frame.acquireCameraImage().use { image ->
                val width = image.width
                val height = image.height

                // Allocate new bitmap for thread safety
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                yuvToRgbConverter.yuvToRgb(image, bitmap)

                val mat = Mat()
                org.opencv.android.Utils.bitmapToMat(bitmap, mat)

                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val grayMat = Mat()
                        org.opencv.imgproc.Imgproc.cvtColor(mat, grayMat, org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY)

                        val descriptors = Mat()
                        val keypoints = MatOfKeyPoint()
                        val orb = ORB.create()
                        orb.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

                        if (descriptors.rows() > 0 && originalDescriptors != null) {
                            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                            val matches = MatOfDMatch()
                            matcher.match(descriptors, originalDescriptors, matches)

                            val matchesList = matches.toList()
                            val goodMatches = matchesList.filter { it.distance < 60 }.size

                            val ratio = if (originalKeypointCount > 0) {
                                goodMatches.toFloat() / originalKeypointCount.toFloat()
                            } else {
                                0f
                            }
                            val progress = (1.0f - ratio).coerceIn(0f, 1f) * 100f

                            // Pass the unique bitmap instance on Main Thread
                            mainHandler.post { onProgressUpdated(progress, bitmap) }
                        }
                        grayMat.release()
                        descriptors.release()
                        keypoints.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in analysis coroutine", e)
                    } finally {
                        mat.release()
                    }
                }
            }
        } catch (e: NotYetAvailableException) {
            if (wasTracking) {
                wasTracking = false
                mainHandler.post { onTrackingFailure("Tracking lost. Move device to re-establish.") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
            pointCloudRenderer.createOnGlThread()
            simpleQuadRenderer.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize renderers", e)
        }
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

        try {
            session!!.setCameraTextureName(backgroundRenderer.textureId)
            displayRotationHelper.updateSessionIfNeeded(session!!)
            val frame = session?.update() ?: return

            backgroundRenderer.draw(frame)

            if (captureNextFrame) {
                captureFrameForFingerprint(frame)
                captureNextFrame = false
            }

            if (System.currentTimeMillis() - lastAnalysisTime > ANALYSIS_INTERVAL_MS && originalDescriptors != null) {
                lastAnalysisTime = System.currentTimeMillis()
                analyzeFrameAsync(frame)
            }

            val camera = frame.camera

            if (camera.trackingState == TrackingState.TRACKING) {
                if (!wasTracking) {
                    wasTracking = true
                    mainHandler.post { onTrackingFailure(null) } // Clear warning
                }
            } else {
                if (wasTracking) {
                    wasTracking = false
                    mainHandler.post { onTrackingFailure("Tracking lost. Point device at grid.") }
                }
                return
            }

            // Projection/View Matrices
            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)
            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            frame.acquirePointCloud().use { pointCloud ->
                pointCloudRenderer.draw(pointCloud, viewmtx, projmtx)
            }

            when (arState) {
                ArState.SEARCHING -> {
                    val planes = session!!.getAllTrackables(Plane::class.java)
                    var hasTrackingPlane = false
                    for (plane in planes) {
                        if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                            planeRenderer.draw(plane, viewmtx, projmtx)
                            hasTrackingPlane = true
                        }
                    }
                    mainHandler.post { onPlanesDetected(hasTrackingPlane) }
                    handleTap(frame)
                }
                ArState.LOCKED -> {
                    val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
                    for (img in updatedAugmentedImages) {
                        if (img.trackingState == TrackingState.TRACKING && img.name.startsWith("target")) {
                            val pose = img.centerPose
                            val poseMatrix = FloatArray(16)
                            pose.toMatrix(poseMatrix, 0)
                            arImagePose = poseMatrix
                            break
                        }
                    }
                    drawArtwork(viewmtx, projmtx)
                }
                ArState.PLACED -> {
                    drawArtwork(viewmtx, projmtx)
                }
            }

        } catch (e: SessionPausedException) {
            mainHandler.post { onTrackingFailure("AR session paused. Please wait.") }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the GL Thread", t)
        }
    }

    private fun drawArtwork(viewMtx: FloatArray, projMtx: FloatArray) {
        val bitmap = overlayBitmap ?: return
        val pose = arImagePose ?: return

        val modelMtx = pose.clone()

        Matrix.rotateM(modelMtx, 0, rotationZ, 0f, 0f, 1f)
        Matrix.rotateM(modelMtx, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMtx, 0, rotationY, 0f, 1f, 0f)
        Matrix.rotateM(modelMtx, 0, -90f, 1f, 0f, 0f)
        Matrix.scaleM(modelMtx, 0, scale, scale, 1f)

        val aspectRatio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
        Matrix.scaleM(modelMtx, 0, aspectRatio, 1f, 1f)

        simpleQuadRenderer.draw(
            modelMtx, viewMtx, projMtx,
            bitmap, opacity, brightness, colorBalanceR, colorBalanceG, colorBalanceB
        )
    }

    private fun handleTap(frame: Frame) {
        val tap = tapQueue.poll() ?: return
        val hitResult = frame.hitTest(tap.first, tap.second)

        for (hit in hitResult) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)) {
                val anchor = hit.createAnchor()
                val poseMatrix = FloatArray(16)
                anchor.pose.toMatrix(poseMatrix, 0)

                arImagePose = poseMatrix
                arState = ArState.PLACED
                mainHandler.post { onAnchorCreated() }
                break
            }
        }
    }

    private fun captureFrameForFingerprint(frame: Frame) {
        try {
            frame.acquireCameraImage().use { image ->
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                yuvToRgbConverter.yuvToRgb(image, bitmap)
                mainHandler.post { onFrameCaptured(bitmap) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
        }
    }

    fun triggerCapture() {
        captureNextFrame = true
    }

    fun setAugmentedImageDatabase(bitmaps: List<Bitmap>) {
        val session = this.session ?: return
        session.pause()
        val config = session.config
        val database = AugmentedImageDatabase(session)

        bitmaps.forEachIndexed { index, bitmap ->
            database.addImage("target_$index", bitmap)
        }

        config.augmentedImageDatabase = database
        session.configure(config)
        session.resume()
        arState = ArState.LOCKED
    }

    fun onResume(activity: Activity) {
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED) {
                    session = Session(context)
                    val config = Config(session)
                    config.updateMode = Config.UpdateMode.BLOCKING
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.focusMode = Config.FocusMode.AUTO

                    // Explicitly ENABLE depth for robustness and occlusion
                    config.depthMode = Config.DepthMode.AUTOMATIC

                    session!!.configure(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception creating session", e)
            }
        }

        try {
            session?.resume()
            displayRotationHelper.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available")
        }
    }

    fun onPause() {
        displayRotationHelper.onPause()
        session?.pause()
    }

    // Required by ArView.kt
    fun cleanup() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session", e)
        } finally {
            session = null
        }
    }

    fun queueTap(x: Float, y: Float) {
        tapQueue.offer(Pair(x, y))
    }

    fun updateOverlayImage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .allowHardware(false)
                    .build()
                val result = context.imageLoader.execute(request)
                val drawable = result.drawable
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    overlayBitmap = drawable.bitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading overlay image", e)
            }
        }
    }

    companion object {
        const val TAG = "ArRenderer"
    }
}