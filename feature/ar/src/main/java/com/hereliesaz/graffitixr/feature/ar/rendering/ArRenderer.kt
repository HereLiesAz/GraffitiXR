package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.SessionPausedException
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

class ArRenderer(
    private val context: Context,
    private val slamManager: SlamManager,
    private val onTrackingChanged: (state: String, pointCount: Int) -> Unit = { _, _ -> }
) : GLSurfaceView.Renderer {

    @Volatile var session: Session? = null
    /** Set to true only after session.resume() completes; false after session.pause(). */
    @Volatile var isSessionResumed: Boolean = false
    private val backgroundRenderer = BackgroundRenderer()
    private val featurePointRenderer = FeaturePointRenderer()
    private var hasSetTextureNames = false

    // Previous camera world-position for inter-frame translation magnitude.
    private var prevPx = 0f; private var prevPy = 0f; private var prevPz = 0f
    private var hasPrevPose = false

    // SLAM computation runs on a dedicated background thread so the GL thread
    // is never blocked by OpenCV optical-flow or voxel-map updates.
    // compareAndSet(false,true) acts as a drop-gate: if the previous frame
    // is still being processed the new one is skipped rather than queued.
    private var monoBuf: ByteBuffer? = null
    private var depthBuf: ByteBuffer? = null
    private val slamBusy = AtomicBoolean(false)
    private val slamExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "slam-worker").also { it.isDaemon = true }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Timber.tag("AR_DEBUG").e(">>> [1] onSurfaceCreated() INITIATED")
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        try {
            backgroundRenderer.createOnGlThread(context)
            featurePointRenderer.createOnGlThread()
            slamManager.createOnGlThread()
            slamManager.resetGLState()
            slamManager.ensureInitialized()
            slamManager.setVisualizationMode(0) // 0 = AR Mode
            Timber.tag("AR_DEBUG").e(">>> [3] SlamManager initialized for AR Mode")
        } catch (e: Exception) {
            Timber.tag("AR_DEBUG").e(e, ">>> [!] FATAL ERROR in onSurfaceCreated")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Timber.tag("AR_DEBUG").e(">>> [4] onSurfaceChanged() triggered. Width: $width, Height: $height")
        GLES30.glViewport(0, 0, width, height)
        slamManager.onSurfaceChanged(width, height)
        // Rotation 0 = portrait; update if the app supports landscape.
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return
        if (!isSessionResumed) return

        try {
            if (!hasSetTextureNames) {
                currentSession.setCameraTextureName(backgroundRenderer.textureId)
                hasSetTextureNames = true
                Timber.tag("AR_DEBUG").e(">>> [5] Camera texture linked to BackgroundRenderer")
            }

            val frame = currentSession.update()
            val camera = frame.camera

            // Always draw the camera feed as the background.
            backgroundRenderer.draw(frame)

            val trackingState = camera.trackingState
            onTrackingChanged(trackingState.name, 0)

            if (trackingState == TrackingState.TRACKING) {
                val viewMatrix = FloatArray(16)
                val projMatrix = FloatArray(16)
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
                slamManager.updateCamera(viewMatrix, projMatrix)

                // Draw ARCore's native tracking feature points as cyan circles.
                try {
                    val pointCloud = frame.acquirePointCloud()
                    try {
                        featurePointRenderer.draw(pointCloud, viewMatrix, projMatrix)
                    } finally {
                        pointCloud.release()
                    }
                } catch (_: Exception) { /* point cloud unavailable this frame */ }

                // Compute inter-frame translation magnitude from ARCore world-space pose.
                val t = camera.pose.translation  // [x, y, z] in metres
                val translationM = if (hasPrevPose) {
                    val dx = t[0] - prevPx; val dy = t[1] - prevPy; val dz = t[2] - prevPz
                    sqrt(dx * dx + dy * dy + dz * dz)
                } else 0.02f  // first frame — use sensible default
                prevPx = t[0]; prevPy = t[1]; prevPz = t[2]; hasPrevPose = true

                // Focal length (fx) in image pixels from ARCore camera intrinsics.
                val focalLengthPx = camera.imageIntrinsics.focalLength[0]

                // Update native kScale before feeding the monocular frame.
                slamManager.setCameraMotion(focalLengthPx, translationM)

                // Feed luminance (Y-plane) to the SLAM pipeline — off GL thread.
                // The ARCore ByteBuffer is only valid until image.close(), so we copy
                // it into a pre-allocated direct buffer before releasing the image.
                try {
                    val image = frame.acquireCameraImage()
                    if (slamBusy.compareAndSet(false, true)) {
                        val src = image.planes[0].buffer
                        val needed = src.remaining()
                        if (monoBuf == null || monoBuf!!.capacity() < needed) {
                            monoBuf = ByteBuffer.allocateDirect(needed)
                        }
                        val buf = monoBuf!!.also { it.clear(); it.put(src); it.rewind() }
                        val w = image.width; val h = image.height
                        slamExecutor.execute {
                            try { slamManager.feedMonocularData(buf, w, h) }
                            finally { slamBusy.set(false) }
                        }
                    }
                    image.close()
                } catch (_: Exception) { /* frame unavailable this tick */ }

                // Feed ARCore DEPTH16 to the SLAM pipeline — off GL thread.
                try {
                    val depthImage = frame.acquireDepthImage16Bits()
                    if (slamBusy.compareAndSet(false, true)) {
                        val src = depthImage.planes[0].buffer
                        val needed = src.remaining()
                        if (depthBuf == null || depthBuf!!.capacity() < needed) {
                            depthBuf = ByteBuffer.allocateDirect(needed)
                        }
                        val buf = depthBuf!!.also { it.clear(); it.put(src); it.rewind() }
                        val w = depthImage.width; val h = depthImage.height
                        slamExecutor.execute {
                            try { slamManager.feedArCoreDepth(buf, w, h) }
                            finally { slamBusy.set(false) }
                        }
                    }
                    depthImage.close()
                } catch (_: Exception) { /* depth not available on this device or frame */ }
            }
        } catch (_: SessionPausedException) {
            // Session paused between the isSessionResumed check and update() — skip this frame.
            isSessionResumed = false
        } catch (e: Exception) {
            Timber.tag("AR_DEBUG").e(e, ">>> [!] CRITICAL EXCEPTION during onDrawFrame")
        }
    }
}
