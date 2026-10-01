package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsicsEstimator
import com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation
import com.hereliesaz.graffitixr.feature.ar.rendering.ProjectionMatrix
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer
import kotlin.math.abs
import timber.log.Timber

/**
 * CameraX tracker used when a standalone phone joins an ARCore-hosted co-op session.
 *
 * There is no KPM page in that project. The host's MobileGS fingerprint is therefore the visual
 * reference: PnP solves camera-from-fingerprint and protocol-v3 metadata composes it into the host
 * wall frame. This analyzer never creates or consumes an ARCore Session.
 */
internal class CoopPeerFingerprintAnalyzer(
    private val context: Context,
    private val cameraId: String,
    private val slam: SlamManager,
    peerFingerprint: ByteArray,
    private val spatialFrame: CoopSpatialFrame,
    private val onFrameTracked: (SphereSlamStandaloneFrame?) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val peerBytes = peerFingerprint.copyOf()
    private var directBuffer: ByteBuffer? = null
    private var lastSeq = 0f
    private var lastGood: SphereSlamStandaloneFrame? = null
    private var lastFreshSolveMs = Long.MIN_VALUE
    @Volatile private var closed = false

    init {
        require(peerBytes.isNotEmpty()) { "peer fingerprint required" }
        require(spatialFrame.fingerprintAvailable) { "spatial frame says no peer fingerprint" }
        slam.ensureInitialized()
        // MobileGS is process-global. A local standalone map belongs to a different coordinate
        // object and must not survive installation of peer geometry.
        slam.clearWallFeatureMap()
        slam.overlayMarkCenterLocal = null
        slam.captureAnchorCam = spatialFrame.fingerprintFromWall.toFloatArray()
        slam.setTrackingPoseValid(false)
        slam.alignToPeer(peerBytes)
        onDiagnostic(
            "Co-op peer tracker installed backend=${spatialFrame.hostBackend} " +
                "scale=${spatialFrame.scale} revision=${spatialFrame.anchorRevision}",
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (closed) return
            val y = image.planes.firstOrNull() ?: return
            val rawWidth = image.width
            val rawHeight = image.height
            val crop = image.cropRect
            if (crop.width() <= 0 || crop.height() <= 0) return
            val rotationDeg = image.imageInfo.rotationDegrees

            val rotated = LumaFrameTransform.packCropAndRotate(
                source = y.buffer,
                sourceWidth = rawWidth,
                sourceHeight = rawHeight,
                rowStride = y.rowStride,
                pixelStride = y.pixelStride,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                rotationDegrees = rotationDeg,
            )
            val rawIntrinsics = CameraIntrinsicsEstimator.estimate(
                context,
                cameraId,
                rawWidth,
                rawHeight,
            ) ?: return
            val cropped = cropCameraIntrinsics(
                intrinsics = rawIntrinsics,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
            )
            val ri = CaptureRotation.rotateIntrinsics(
                cropped.fx,
                cropped.fy,
                cropped.cx,
                cropped.cy,
                cropped.width.toFloat(),
                cropped.height.toFloat(),
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
            slam.setLiveIntrinsics(intrinsics.fx, intrinsics.fy, intrinsics.cx, intrinsics.cy)
            slam.setTrackingPoseValid(false)

            var buffer = directBuffer
            if (buffer == null || buffer.capacity() < rotated.bytes.size) {
                buffer = ByteBuffer.allocateDirect(rotated.bytes.size)
                directBuffer = buffer
            }
            buffer.clear()
            buffer.put(rotated.bytes)
            buffer.flip()
            slam.feedLumaFrame(buffer, rotated.width, rotated.height, image.imageInfo.timestamp)

            val solved = CoopPeerWallPoseSolver.solve(slam.getRelocResult(), spatialFrame)
            if (solved != null && solved.seq != lastSeq) {
                lastSeq = solved.seq
                val projection = ProjectionMatrix.buildFrom(intrinsics)
                val tracked = SphereSlamStandaloneFrame(
                    viewMatrix = solved.viewMatrix,
                    projMatrix = projection,
                    frameAspect = rotated.width.toFloat() / rotated.height.toFloat(),
                    frameHeightPixels = rotated.height,
                    timestampNs = image.imageInfo.timestamp,
                    pageNo = -1,
                    reprojectionError = -1f,
                    inlierCount = solved.inliers,
                    unitsPerPixel = unitsPerPixel(
                        solved.viewMatrix,
                        projection,
                        rotated.height,
                    ),
                    observationAgeMs = null,
                    matchDurationMs = 0f,
                    source = SphereSlamStandalonePoseSource.PEER_FINGERPRINT,
                )
                lastGood = tracked
                lastFreshSolveMs = android.os.SystemClock.elapsedRealtime()
                onFrameTracked(tracked)
                return
            }

            // Native relocalization is intentionally throttled/asynchronous, so a tiny hold prevents
            // flashing between worker solves. It MUST be bounded: after the same 400 ms window used
            // by the normal standalone visual bridge, the pose is stale camera state and keeping it
            // would make the mural ride the screen after the peer target leaves view.
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val held = lastGood?.takeIf {
                lastFreshSolveMs != Long.MIN_VALUE &&
                    nowMs - lastFreshSolveMs <= MAX_PEER_POSE_HOLD_MS
            }
            if (held == null) lastGood = null
            onFrameTracked(held)
        } catch (t: Throwable) {
            if (!closed) {
                Timber.w(t, "Co-op peer fingerprint analysis failed")
                onDiagnostic("Co-op peer tracker failure=${t.javaClass.simpleName}")
                onFrameTracked(null)
            }
        } finally {
            image.close()
        }
    }

    private fun unitsPerPixel(view: FloatArray, projection: FloatArray, frameHeight: Int): Float {
        if (frameHeight <= 0 || projection[5] == 0f) return 0f
        val depth = abs(view[14])
        return depth * 2f * (1f / projection[5]) / frameHeight.toFloat()
    }

    private companion object {
        const val MAX_PEER_POSE_HOLD_MS = 400L
    }

    override fun close() {
        closed = true
        slam.setTrackingPoseValid(false)
        directBuffer = null
        lastGood = null
    }
}
