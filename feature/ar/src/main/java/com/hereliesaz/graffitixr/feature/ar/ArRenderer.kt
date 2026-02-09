package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.widget.Toast
import android.view.PixelCopy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.graphics.Bitmap
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.PointCloud
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.feature.ar.rendering.AugmentedImageRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.PlaneRenderer
import com.hereliesaz.graffitixr.feature.ar.rendering.PointCloudRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(private val context: Context) : GLSurfaceView.Renderer, DefaultLifecycleObserver {

    val view: GLSurfaceView = GLSurfaceView(context).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this@ArRenderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    var session: Session? = null
        private set

    val slamManager = SlamManager()

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer(context, context)
    private val pointCloudRenderer = PointCloudRenderer()
    private val augmentedImageRenderer = AugmentedImageRenderer()

    // State flags
    private var showPointCloud = false
    private var isFlashlightOn = false

    // Matrix caches to avoid allocation in draw loop
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    // Display rotation helper
    private val displayRotationHelper = DisplayRotationHelper(context)

    override fun onResume(owner: LifecycleOwner) {
        onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        onPause()
    }

    fun onResume() {
        if (session == null) {
            try {
                session = Session(context).apply {
                    val config = Config(this)
                    config.focusMode = Config.FocusMode.AUTO
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    configure(config)
                }
            } catch (e: Exception) {
                handleSessionException(e)
                return
            }
        }

        try {
            session?.resume()
            view.onResume()
            displayRotationHelper.onResume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(context, "Camera unavailable", Toast.LENGTH_LONG).show()
        }
    }

    fun onPause() {
        displayRotationHelper.onPause()
        view.onPause()
        session?.pause()
    }

    fun cleanup() {
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Initialize Native Engine (MobileGS)
        slamManager.init(context.assets)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread(context)
        pointCloudRenderer.createOnGlThread()
        augmentedImageRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayRotationHelper.onSurfaceChanged(width, height)
        GLES20.glViewport(0, 0, width, height)
        slamManager.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear screen to avoid artifacts if native draw fails or is partial
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val session = session ?: return

        displayRotationHelper.updateSessionIfNeeded(session)

        try {
            session.setCameraTextureName(backgroundRenderer.textureId)
            val frame = session.update()
            val camera = frame.camera

            // Draw the camera background
            backgroundRenderer.draw(frame)

            // Get projection matrix.
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

            // Get camera matrix and draw.
            camera.getViewMatrix(viewMatrix, 0)

            // Visualize tracked points.
            val pointCloud = frame.acquirePointCloud()
            pointCloudRenderer.update(pointCloud)
            pointCloudRenderer.draw(viewMatrix, projectionMatrix)
            pointCloud.release()

            // Visualize planes.
            planeRenderer.drawPlanes(
                session.getAllTrackables(Plane::class.java),
                viewMatrix,
                projectionMatrix
            )

            // Visualize Augmented Images (Anchors)
            val augmentedImages = session.getAllTrackables(AugmentedImage::class.java)
            for (augmentedImage in augmentedImages) {
                if (augmentedImage.trackingState == TrackingState.TRACKING) {
                    augmentedImageRenderer.draw(
                        viewMatrix,
                        projectionMatrix,
                        augmentedImage.centerPose,
                        augmentedImage.extentX,
                        augmentedImage.extentZ
                    )
                }
            }

            // Pass ARCore frame data to Native Engine (MobileGS)
            // Use the real matrix update method, not the placeholder
            slamManager.updateCamera(viewMatrix, projectionMatrix)

            try {
                val depthImage = frame.acquireDepthImage16Bits()
                if (depthImage != null) {
                    val buffer = depthImage.planes[0].buffer
                    val width = depthImage.width
                    val height = depthImage.height
                    slamManager.feedDepthData(buffer, width, height)
                    depthImage.close()
                }
            } catch (e: Exception) {
                // Depth not available or error acquiring
            }

            // Render Native SLAM content (if enabled)
            slamManager.draw(showPointCloud)

        } catch (t: Throwable) {
            // Avoid crashing on render loop errors
            t.printStackTrace()
        }
    }

    // --- Interaction Hooks ---

    fun setShowPointCloud(enable: Boolean) {
        this.showPointCloud = enable
    }

    fun setFlashlight(enable: Boolean) {
        this.isFlashlightOn = enable
        val session = session ?: return
        val config = session.config

        try {
            config.flashMode = if (enable) Config.FlashMode.TORCH else Config.FlashMode.OFF
            session.configure(config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Pauses the AR session to re-configure it with a new AugmentedImageDatabase containing
     * the provided bitmap. This effectively creates a new visual anchor for the system to track.
     *
     * @param bitmap The image to track (target).
     * @param name A unique identifier for the target image.
     */
    fun setupAugmentedImageDatabase(bitmap: Bitmap, name: String) {
        val session = session ?: return
        try {
            // Pause session to configure
            session.pause()

            val config = Config(session)
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.depthMode = Config.DepthMode.AUTOMATIC

            // Create database
            val database = AugmentedImageDatabase(session)
            database.addImage(name, bitmap)
            config.augmentedImageDatabase = database

            session.configure(config)
            session.resume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Captures the current content of the GLSurfaceView into a Bitmap using PixelCopy.
     * This is used for creating targets from the live AR view.
     *
     * @param onCaptured Callback triggered with the resulting Bitmap.
     */
    fun captureFrame(onCaptured: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(
            view.width,
            view.height,
            Bitmap.Config.ARGB_8888
        )
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        try {
            PixelCopy.request(
                view,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        onCaptured(bitmap)
                    }
                },
                handler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleSessionException(e: Exception) {
        val message = when (e) {
            is UnavailableArcoreNotInstalledException -> "Please install ARCore"
            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            else -> "Failed to create AR session"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
