package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.*
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.KeyPointData
import com.hereliesaz.graffitixr.rendering.*
import com.hereliesaz.graffitixr.slam.SlamManager
import com.hereliesaz.graffitixr.utils.DisplayRotationHelper
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded
import kotlinx.coroutines.*
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

    // ... (Standard members) ...
    // Assuming member variables from previous context (backgroundRenderer, etc.) are present
    // Re-declaring critical ones for the snippet correctness:
    private val sessionLock = ReentrantLock()
    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val simpleQuadRenderer = SimpleQuadRenderer()
    private val miniMapRenderer = MiniMapRenderer()
    private val displayRotationHelper = DisplayRotationHelper(context)
    private val slamManager = SlamManager()

    @Volatile var session: Session? = null
    var isAnchorReplacementAllowed: Boolean = false

    // ...

    // Fix: Fingerprint generation logic with correct types
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
                val kpData = kpsList.map { kp ->
                    KeyPointData(kp.pt.x.toFloat(), kp.pt.y.toFloat(), kp.size, kp.angle, kp.response, kp.octave, kp.class_id)
                }

                val descData = ByteArray(descriptors.rows() * descriptors.cols() * descriptors.channels())
                descriptors.get(0, 0, descData)

                return@withContext Fingerprint(
                    keypoints = kpData,
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
            orb.release()
        }
    }

    // Stub for flashlight to fix VM error
    fun setFlashlight(enabled: Boolean) {
        // ... config update logic ...
    }

    fun triggerCapture() { /* ... */ }

    // ... (Lifecycle methods onSurfaceCreated etc from previous turn) ...
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { /* ... */ }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { /* ... */ }
    override fun onDrawFrame(gl: GL10?) { /* ... */ }

    fun onResume(activity: android.app.Activity) { /* ... */ }
    fun onPause() { /* ... */ }
    fun cleanup() { /* ... */ }
    fun queueTap(x: Float, y: Float) { /* ... */ }
}