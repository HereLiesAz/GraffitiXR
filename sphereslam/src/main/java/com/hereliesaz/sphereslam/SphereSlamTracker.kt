package com.hereliesaz.sphereslam

import com.hereliesaz.graffitixr.nativebridge.KpmBridge
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * KPM-backed wall tracker that runs beside ARCore.
 *
 * This class never produces the renderer's primary view/projection matrices. ARCore keeps that job.
 * SphereSLAM consumes copies of the same camera luminance frames on a private worker and publishes
 * wall-relative observations for relocalization and drift correction.
 */
class SphereSlamTracker(
    private val native: Native = KpmNative,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sphereslam-kpm").apply { isDaemon = true }
    },
) : AutoCloseable {

    data class CameraModel(
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
    ) {
        init {
            require(width > 0 && height > 0)
            require(fx > 0f && fy > 0f)
        }
    }

    data class Observation(
        val timestampNs: Long,
        val pageNo: Int,
        val error: Float,
        val inliers: Int,
        /**
         * artoolkitX page-to-camera 3x4 transform, row-major.
         *
         * KPM generates page coordinates in millimetres from the supplied reference DPI. Until the
         * capture path supplies a wall-metric DPI, translation scale is relative. Consumers must not
         * replace ARCore's metric pose with this transform.
         */
        val pageToCamera3x4: FloatArray,
    ) {
        init {
            require(pageToCamera3x4.size == 12)
        }
    }

    interface Native {
        val available: Boolean
        fun create(camera: CameraModel): Long
        fun destroy(handle: Long)
        fun setReference(
            handle: Long,
            luma: ByteArray,
            width: Int,
            height: Int,
            dpi: Float,
            pageNo: Int,
            imageNo: Int,
            maxFeatures: Int,
        ): Boolean
        fun match(handle: Long, luma: ByteArray, timestampNs: Long): Observation?
    }

    private data class PendingFrame(val luma: ByteArray, val timestampNs: Long)

    private val closed = AtomicBoolean(false)
    private val latest = AtomicReference<Observation?>(null)
    private val pendingFrame = AtomicReference<PendingFrame?>(null)
    private val drainScheduled = AtomicBoolean(false)

    @Volatile private var cameraModel: CameraModel? = null
    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var referenceReady: Boolean = false
    @Volatile private var referenceGeometry: SphereSlamPoseMath.PageGeometry? = null

    val isNativeAvailable: Boolean get() = native.available
    val isReferenceReady: Boolean get() = referenceReady
    fun latestObservation(): Observation? = latest.get()
    fun currentReferenceGeometry(): SphereSlamPoseMath.PageGeometry? = referenceGeometry

    fun configure(camera: CameraModel) {
        if (closed.get()) return
        if (camera == cameraModel && nativeHandle != 0L) return
        worker.execute {
            if (closed.get()) return@execute
            if (camera == cameraModel && nativeHandle != 0L) return@execute
            destroyHandle()
            cameraModel = camera
            referenceReady = false
            referenceGeometry = null
            latest.set(null)
            nativeHandle = if (native.available) native.create(camera) else 0L
        }
    }

    /**
     * Start a fresh wall atlas with this calibrated camera. Used by a new target capture so pages
     * from an older wall cannot contaminate the new reference. The reset and following page-add are
     * ordered on the same worker.
     */
    fun reset(camera: CameraModel) {
        if (closed.get()) return
        worker.execute {
            if (closed.get()) return@execute
            destroyHandle()
            cameraModel = camera
            referenceReady = false
            referenceGeometry = null
            latest.set(null)
            nativeHandle = if (native.available) native.create(camera) else 0L
        }
    }

    fun setReference(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        dpi: Float = 72f,
        pageNo: Int = 0,
        imageNo: Int = 0,
        maxFeatures: Int = 500,
    ) {
        if (closed.get()) return
        require(dpi > 0f)
        require(maxFeatures > 0)
        val packed = packLuma(luma, width, height, rowStride)
        worker.execute {
            if (closed.get()) return@execute
            val handle = nativeHandle
            val model = cameraModel
            // The calibrated session dimensions describe LIVE camera frames. A planar reference is
            // allowed to be a separately rectified image with its own dimensions; artoolkitX KPM
            // stores page image geometry independently from camera calibration.
            if (handle == 0L || model == null) {
                referenceReady = false
                referenceGeometry = null
                return@execute
            }
            val accepted = native.setReference(
                handle, packed, width, height, dpi, pageNo, imageNo, maxFeatures
            )
            referenceReady = accepted
            referenceGeometry = if (accepted) {
                SphereSlamPoseMath.pageGeometry(width, height, dpi)
            } else {
                null
            }
            latest.set(null)
        }
    }

    /**
     * Synchronously stop publishing/matching the current page before an asynchronous replacement is
     * prepared. The native handle is intentionally left alive until [reset] supplies the next camera
     * model, but no old observation can escape after this call.
     */
    fun clearReference() {
        referenceReady = false
        referenceGeometry = null
        pendingFrame.set(null)
        latest.set(null)
    }

    /**
     * Copies and submits one live frame without blocking on KPM. Only the newest unprocessed frame
     * is retained, so a slow matcher cannot build latency behind ARCore.
     */
    fun submitFrame(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    ) {
        if (closed.get() || !referenceReady) return
        val model = cameraModel ?: return
        if (model.width != width || model.height != height) return
        pendingFrame.set(PendingFrame(packLuma(luma, width, height, rowStride), timestampNs))
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (!closed.get()) {
                    val frame = pendingFrame.getAndSet(null) ?: break
                    val handle = nativeHandle
                    if (handle != 0L && referenceReady) {
                        native.match(handle, frame.luma, frame.timestampNs)?.let(latest::set)
                    }
                }
            } finally {
                drainScheduled.set(false)
                if (!closed.get() && pendingFrame.get() != null) scheduleDrain()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingFrame.set(null)
        worker.execute {
            destroyHandle()
            latest.set(null)
        }
        worker.shutdown()
    }

    private fun destroyHandle() {
        val handle = nativeHandle
        nativeHandle = 0L
        referenceReady = false
        referenceGeometry = null
        if (handle != 0L) native.destroy(handle)
    }

    companion object {
        internal fun packLuma(source: ByteBuffer, width: Int, height: Int, rowStride: Int): ByteArray {
            require(width > 0 && height > 0)
            require(rowStride >= width)
            val base = source.position()
            val requiredLastIndex = base + (height - 1) * rowStride + width
            require(requiredLastIndex <= source.limit())
            val out = ByteArray(width * height)
            val duplicate = source.duplicate()
            var dst = 0
            for (row in 0 until height) {
                duplicate.position(base + row * rowStride)
                duplicate.get(out, dst, width)
                dst += width
            }
            return out
        }
    }

    private object KpmNative : Native {
        override val available: Boolean get() = KpmBridge.isAvailable()

        override fun create(camera: CameraModel): Long = KpmBridge.createCalibratedSession(
            camera.width, camera.height, camera.fx, camera.fy, camera.cx, camera.cy
        )

        override fun destroy(handle: Long) = KpmBridge.destroySession(handle)

        override fun setReference(
            handle: Long,
            luma: ByteArray,
            width: Int,
            height: Int,
            dpi: Float,
            pageNo: Int,
            imageNo: Int,
            maxFeatures: Int,
        ): Boolean {
            val direct = ByteBuffer.allocateDirect(luma.size)
            direct.put(luma).rewind()
            return KpmBridge.addPlanarPage(
                handle,
                direct,
                width,
                height,
                dpi,
                pageNo,
                imageNo,
                maxFeatures,
            ) > 0
        }

        override fun match(handle: Long, luma: ByteArray, timestampNs: Long): Observation? {
            val direct = ByteBuffer.allocateDirect(luma.size)
            direct.put(luma).rewind()
            val out = FloatArray(KpmBridge.MATCH_OUTPUT_FLOATS)
            val pageNo = KpmBridge.matchPlanar(handle, direct, out)
            if (pageNo < 0) return null
            return Observation(
                timestampNs = timestampNs,
                pageNo = pageNo,
                error = out[12],
                inliers = out[13].toInt(),
                pageToCamera3x4 = out.copyOfRange(0, 12),
            )
        }
    }
}
