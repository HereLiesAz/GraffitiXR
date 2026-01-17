package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.MiniMapRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.rendering.PointCloudRenderer
import com.hereliesaz.graffitixr.rendering.SimpleQuadRenderer
import com.hereliesaz.graffitixr.slam.SlamManager
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
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
    var isAnchorReplacementAllowed: Boolean = false
    var showMiniMap: Boolean = false
    var showGuide: Boolean = true

    // Texture for the camera feed
    private var backgroundTextureId = -1

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
        // Queue tap event
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        // 1. Create the texture for the camera feed
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        backgroundTextureId = textures[0]
        val textureTarget = android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, backgroundTextureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // 2. Initialize renderers
        backgroundRenderer.createOnGlThread(context)
        planeRenderer.createOnGlThread(context, "models/trigrid.png")
        pointCloudRenderer.createOnGlThread(context)
        simpleQuadRenderer.createOnGlThread(context) // Ensure this method exists in SimpleQuadRenderer
        miniMapRenderer.createOnGlThread(context)

        // 3. Connect texture to session if ready
        session?.setCameraTextureName(backgroundTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (session == null) return

        displayRotationHelper.updateSessionIfNeeded(session!!)

        try {
            session!!.setCameraTextureName(backgroundTextureId)
            val frame = session!!.update()
            val camera = frame.camera

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
            pointCloud.release()

            // Detect Planes
            if (hasTrackingPlane()) {
                onPlanesDetected(true)
                planeRenderer.drawPlanes(session!!, camera.displayOrientedPose, projmtx)
            } else {
                onPlanesDetected(false)
            }

        } catch (t: Throwable) {
            Log.e("ArRenderer", "Exception on the OpenGL thread", t)
        }
    }

    private fun hasTrackingPlane(): Boolean {
        session?.getAllTrackables(com.google.ar.core.Plane::class.java)?.forEach { plane ->
            if (plane.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                return true
            }
        }
        return false
    }

    fun onResume(activity: android.app.Activity) {
        displayRotationHelper.onResume()
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
    }
}