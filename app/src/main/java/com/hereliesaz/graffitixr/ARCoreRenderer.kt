package com.hereliesaz.graffitixr

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.rendering.AugmentedImageRenderer
import com.hereliesaz.graffitixr.rendering.PlaneRenderer
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARCoreRenderer(private val arCoreManager: ARCoreManager) : GLSurfaceView.Renderer {

    private val augmentedImageRenderer = AugmentedImageRenderer()
    private val planeRenderer = PlaneRenderer()
    private val trackedImages = mutableMapOf<Int, Pair<AugmentedImage, AugmentedImageRenderer>>()
    private val orb by lazy { ORB.create() }
    private val matcher by lazy { DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING) }
    private var fingerprintKeypoints: MatOfKeyPoint? = null
    private var fingerprintDescriptors: Mat? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        arCoreManager.onSurfaceCreated()
        arCoreManager.backgroundRenderer.createOnGlThread()
        augmentedImageRenderer.createOnGlThread()
        arCoreManager.pointCloudRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: width=$width, height=$height")
        GLES20.glViewport(0, 0, width, height)
        arCoreManager.displayRotationHelper.onSurfaceChanged(width, height)
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Log.d(TAG, "onDrawFrame") // Too spammy
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frame: Frame = arCoreManager.onDrawFrame(surfaceWidth, surfaceHeight) ?: return

        if (frame.timestamp == 0L) {
            Log.v(TAG, "onDrawFrame: Frame timestamp is 0")
            return
        }

        arCoreManager.backgroundRenderer.draw(frame)

        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
        frame.camera.getViewMatrix(viewMatrix, 0)

        frame.acquirePointCloud().use { pointCloud ->
            arCoreManager.pointCloudRenderer.draw(pointCloud, viewMatrix, projectionMatrix)
        }

        val planes = arCoreManager.session?.getAllTrackables(Plane::class.java) ?: emptyList()
        for (plane in planes) {
            if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                planeRenderer.draw(plane, viewMatrix, projectionMatrix)
            }
        }

        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
        if (updatedAugmentedImages.isNotEmpty()) {
            Log.d(TAG, "Updated augmented images: ${updatedAugmentedImages.size}")
        }

        for (augmentedImage in updatedAugmentedImages) {
            if (augmentedImage.trackingState == TrackingState.TRACKING) {
                if (!trackedImages.containsKey(augmentedImage.index)) {
                    val renderer = AugmentedImageRenderer()
                    renderer.createOnGlThread()
                    trackedImages[augmentedImage.index] = Pair(augmentedImage, renderer)
                }
                trackedImages[augmentedImage.index]?.let { (image, renderer) ->
                    val projectionMatrix = FloatArray(16)
                    val viewMatrix = FloatArray(16)
                    frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                    frame.camera.getViewMatrix(viewMatrix, 0)
                    renderer.draw(viewMatrix, projectionMatrix, image.centerPose, image.extentX, image.extentZ)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ARCoreRenderer"
    }
}
