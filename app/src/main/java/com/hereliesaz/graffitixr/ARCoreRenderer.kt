package com.hereliesaz.graffitixr

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.rendering.AugmentedImageRenderer
import android.util.Log
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import org.opencv.core.Mat
import org.opencv.android.Utils
import org.opencv.imgproc.Imgproc
import org.opencv.features2d.ORB
import org.opencv.features2d.DescriptorMatcher
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfDMatch
import org.opencv.calib3d.Calib3d
import org.opencv.core.MatOfPoint2f
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLUtils

class ARCoreRenderer(private val arCoreManager: ARCoreManager, private val context: Context) : GLSurfaceView.Renderer {

    private val backgroundRenderer = arCoreManager.backgroundRenderer
    private val augmentedImageRenderer = AugmentedImageRenderer()
    private val trackedImages = mutableMapOf<Int, Pair<AugmentedImage, AugmentedImageRenderer>>()
    private val orb by lazy { ORB.create() }
    private val matcher by lazy { DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING) }
    private var fingerprintKeypoints: MatOfKeyPoint? = null
    private var fingerprintDescriptors: Mat? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var textureId = -1


    fun updateTexture(uri: Uri) {
        val bitmap = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        }
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        textureId = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        bitmap.recycle()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer.createOnGlThread()
        augmentedImageRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        arCoreManager.displayRotationHelper.onSurfaceChanged(width, height)
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frame: Frame = arCoreManager.onDrawFrame(surfaceWidth, surfaceHeight) ?: return
        backgroundRenderer.draw(frame)

        val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)

        if (textureId != -1) {
            for (augmentedImage in updatedAugmentedImages) {
                if (augmentedImage.trackingState == TrackingState.TRACKING) {
                    if (!trackedImages.containsKey(augmentedImage.index)) {
                        val renderer = AugmentedImageRenderer()
                        renderer.createOnGlThread()
                        trackedImages[augmentedImage.index] = Pair(augmentedImage, renderer)
                    }
                    val (image, renderer) = trackedImages[augmentedImage.index]!!
                    val projectionMatrix = FloatArray(16)
                    val viewMatrix = FloatArray(16)
                    frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                    frame.camera.getViewMatrix(viewMatrix, 0)

                    renderer.draw(viewMatrix, projectionMatrix, image.centerPose, image.extentX, image.extentZ, textureId)
                }
            }
        }
    }
}
