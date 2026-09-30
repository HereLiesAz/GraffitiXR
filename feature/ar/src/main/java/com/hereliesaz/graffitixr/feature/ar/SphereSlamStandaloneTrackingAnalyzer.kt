package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsicsEstimator
import com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation
import com.hereliesaz.graffitixr.feature.ar.rendering.ProjectionMatrix
import com.hereliesaz.graffitixr.feature.ar.util.RotationDeltaMath
import com.hereliesaz.sphereslam.SphereSlamCalibration
import com.hereliesaz.sphereslam.SphereSlamStandaloneSession
import java.nio.ByteBuffer
import timber.log.Timber

/**
 * Display-oriented reference image consumed by the standalone SphereSLAM CameraX path.
 *
 * [referenceWidthMeters] may be a measured physical width or a normalized renderer width. The
 * initial no-ARCore UI deliberately uses a normalized width of 1.0 and sets [physicallyMetric] false:
 * visual wall registration is then self-consistent, while the app does not pretend it knows a real
 * tape-measure distance that was never measured.
 */
data class SphereSlamStandaloneReferenceImage(
    val luma: ByteArray,
    val width: Int,
    val height: Int,
    val referenceWidthMeters: Float = 1f,
    val physicallyMetric: Boolean = false,
) {
    init {
        require(width > 0 && height > 0)
        require(luma.size == width * height)
        require(referenceWidthMeters.isFinite() && referenceWidthMeters > 0f)
    }
}

enum class SphereSlamStandalonePoseSource {
    KPM,
    IMU_BRIDGE,
}

data class SphereSlamStandaloneFrame(
    val viewMatrix: FloatArray,
    val projMatrix: FloatArray,
    val frameAspect: Float,
    val timestampNs: Long,
    val pageNo: Int,
    val reprojectionError: Float,
    val inlierCount: Int,
    /** Wall/render units represented by one vertical display-frame pixel at the target centre. */
    val unitsPerPixel: Float,
    val source: SphereSlamStandalonePoseSource,
) {
    init {
        require(viewMatrix.size == 16)
        require(projMatrix.size == 16)
    }
}

/**
 * CameraX ImageAnalysis -> calibrated KPM wall pose for phones that cannot run ARCore.
 *
 * This is deliberately synchronous on the ImageAnalysis executor: each callback either has a KPM
 * pose for THIS frame, a short IMU-bridged hold of the last good pose, or no pose. There is no
 * asynchronous "latest match" that can silently pair an old wall observation with a newer camera
 * frame.
 *
 * The IMU bridge is rotation-only and lasts at most [maxBridgeMs]. It applies the same camera-space
 * rotation delta to the view rotation and translation column, preserving the held camera centre
 * during a pure rotation. It never double-integrates accelerometer values or invents displacement.
 */
class SphereSlamStandaloneTrackingAnalyzer(
    private val context: Context,
    private val cameraId: String,
    private val referenceImage: SphereSlamStandaloneReferenceImage,
    private val onFrameTracked: (SphereSlamStandaloneFrame?) -> Unit,
    private val onReferenceReady: (SphereSlamStandaloneSession.Reference) -> Unit = {},
    private val onFatalError: (Throwable) -> Unit = {},
    private val bridge: GyroOrientationBridge = GyroOrientationBridge(context),
    private val maxBridgeMs: Long = 400L,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private data class SessionKey(
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
    )

    private var session: SphereSlamStandaloneSession? = null
    private var sessionKey: SessionKey? = null
    private var lastGood: SphereSlamStandaloneFrame? = null
    private var frameBuffer: ByteBuffer? = null
    @Volatile private var closed = false
    @Volatile private var fatal = false

    fun start() {
        if (!closed) bridge.start()
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (closed || fatal) return
            val planes = image.planes
            if (planes.isEmpty()) return
            val y = planes[0]
            val rawWidth = image.width
            val rawHeight = image.height
            val rotationDeg = image.imageInfo.rotationDegrees

            val rotated = LumaFrameTransform.packAndRotate(
                source = y.buffer,
                width = rawWidth,
                height = rawHeight,
                rowStride = y.rowStride,
                pixelStride = y.pixelStride,
                rotationDegrees = rotationDeg,
            )

            val rawIntrinsics = CameraIntrinsicsEstimator.estimate(
                context,
                cameraId,
                rawWidth,
                rawHeight,
            ) ?: return
            val ri = CaptureRotation.rotateIntrinsics(
                rawIntrinsics.fx,
                rawIntrinsics.fy,
                rawIntrinsics.cx,
                rawIntrinsics.cy,
                rawWidth.toFloat(),
                rawHeight.toFloat(),
                rotationDeg,
            )
            val intrinsics = CameraIntrinsics(
                fx = ri[0],
                fy = ri[1],
                cx = ri[2],
                cy = ri[3],
                width = rotated.width,
                height = rotated.height,
            )

            val active = ensureSession(intrinsics)
            val pendingImuReference = bridge.captureReferenceCandidate(rotationDeg)
            val timestampNs = image.imageInfo.timestamp
            val projection = ProjectionMatrix.buildFrom(intrinsics)
            val direct = directFrame(rotated.bytes)

            val pose = active.match(direct, timestampNs)
            if (pose != null) {
                pendingImuReference?.let(bridge::commitReference)
                val tracked = SphereSlamStandaloneFrame(
                    viewMatrix = pose.viewMatrix,
                    projMatrix = projection,
                    frameAspect = rotated.width.toFloat() / rotated.height.toFloat(),
                    timestampNs = timestampNs,
                    pageNo = pose.pageNo,
                    reprojectionError = pose.reprojectionError,
                    inlierCount = pose.inlierCount,
                    unitsPerPixel = unitsPerPixel(
                        pose.viewMatrix,
                        projection,
                        rotated.height,
                    ),
                    source = SphereSlamStandalonePoseSource.KPM,
                )
                lastGood = tracked
                onFrameTracked(tracked)
                return
            }

            onFrameTracked(bridgeLastGood(projection, rotated, timestampNs))
        } catch (t: Throwable) {
            if (!closed) {
                fatal = true
                Timber.e(t, "Standalone SphereSLAM analyzer failed")
                onFrameTracked(null)
                onFatalError(t)
            }
        } finally {
            image.close()
        }
    }

    private fun ensureSession(intrinsics: CameraIntrinsics): SphereSlamStandaloneSession {
        val key = SessionKey(
            intrinsics.width,
            intrinsics.height,
            intrinsics.fx,
            intrinsics.fy,
            intrinsics.cx,
            intrinsics.cy,
        )
        val existing = session
        if (existing != null && key == sessionKey) return existing

        existing?.close()
        bridge.clearReference()
        lastGood = null

        val created = SphereSlamStandaloneSession(
            frameWidth = intrinsics.width,
            frameHeight = intrinsics.height,
            calibration = SphereSlamCalibration(
                intrinsics.fx,
                intrinsics.fy,
                intrinsics.cx,
                intrinsics.cy,
            ),
        )
        try {
            val refBuffer = ByteBuffer.allocateDirect(referenceImage.luma.size).apply {
                put(referenceImage.luma)
                flip()
            }
            val reference = created.addReference(
                luma = refBuffer,
                width = referenceImage.width,
                height = referenceImage.height,
                referenceWidthMeters = referenceImage.referenceWidthMeters,
                physicallyMetric = referenceImage.physicallyMetric,
            )
            session = created
            sessionKey = key
            onReferenceReady(reference)
            return created
        } catch (t: Throwable) {
            created.close()
            throw t
        }
    }

    private fun bridgeLastGood(
        projection: FloatArray,
        frame: RotatedLuma,
        timestampNs: Long,
    ): SphereSlamStandaloneFrame? {
        val held = lastGood ?: return null
        val elapsed = bridge.msSinceReference()
        if (elapsed < 0L || elapsed > maxBridgeMs) {
            lastGood = null
            return null
        }
        val delta = bridge.cameraRotationDelta() ?: return null
        return held.copy(
            viewMatrix = rotateViewPoseKeepingCameraCenter(held.viewMatrix, delta),
            projMatrix = projection,
            frameAspect = frame.width.toFloat() / frame.height.toFloat(),
            timestampNs = timestampNs,
            source = SphereSlamStandalonePoseSource.IMU_BRIDGE,
        )
    }

    private fun unitsPerPixel(
        viewMatrix: FloatArray,
        projection: FloatArray,
        frameHeightPixels: Int,
    ): Float {
        if (frameHeightPixels <= 0 || projection[5] == 0f) return 0f
        val depth = kotlin.math.abs(viewMatrix[14])
        val tanHalfFovY = 1f / projection[5]
        return depth * 2f * tanHalfFovY / frameHeightPixels.toFloat()
    }

    private fun directFrame(bytes: ByteArray): ByteBuffer {
        var buffer = frameBuffer
        if (buffer == null || buffer.capacity() < bytes.size) {
            buffer = ByteBuffer.allocateDirect(bytes.size)
            frameBuffer = buffer
        }
        buffer.clear()
        buffer.put(bytes)
        buffer.flip()
        return buffer
    }

    override fun close() {
        if (closed) return
        closed = true
        bridge.stop()
        bridge.clearReference()
        lastGood = null
        session?.close()
        session = null
        sessionKey = null
        frameBuffer = null
    }
}

internal fun rotateViewPoseKeepingCameraCenter(
    viewMatrix: FloatArray,
    deltaCameraRowMajor: FloatArray,
): FloatArray {
    require(viewMatrix.size == 16)
    require(deltaCameraRowMajor.size == 9)

    val rotation = FloatArray(9)
    for (row in 0..2) {
        for (col in 0..2) {
            rotation[row * 3 + col] = viewMatrix[col * 4 + row]
        }
    }
    val translation = floatArrayOf(viewMatrix[12], viewMatrix[13], viewMatrix[14])
    val rotatedR = RotationDeltaMath.multiplyMat3(deltaCameraRowMajor, rotation)
    val rotatedT = RotationDeltaMath.multiplyMat3Vec3(deltaCameraRowMajor, translation)

    val out = FloatArray(16)
    for (row in 0..2) {
        for (col in 0..2) {
            out[col * 4 + row] = rotatedR[row * 3 + col]
        }
    }
    out[12] = rotatedT[0]
    out[13] = rotatedT[1]
    out[14] = rotatedT[2]
    out[15] = 1f
    return out
}
