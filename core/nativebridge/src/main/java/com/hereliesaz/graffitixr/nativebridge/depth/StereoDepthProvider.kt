package com.hereliesaz.graffitixr.nativebridge.depth

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the ingestion of stereo camera feeds into the native SLAM engine.
 * Launders managed [ByteArray]s through persistent direct [ByteBuffer]s to satisfy
 * the C++ engine's zero-copy memory demands without provoking the JVM garbage collector.
 */
@Singleton
class StereoDepthProvider @Inject constructor(
    private val slamManager: SlamManager
) {
    private var directLeftBuffer: ByteBuffer? = null
    private var directRightBuffer: ByteBuffer? = null
    private var currentAllocationSize = 0

    // Temporal stereo state (single-camera, consecutive frames)
    private var prevFrameBuffer: ByteBuffer? = null
    private var stereoLeft: ByteBuffer? = null
    private var stereoRight: ByteBuffer? = null
    private var stereoFrameSize: Int = 0
    private var prevWidth: Int = 0
    private var prevHeight: Int = 0

    val isDualLensActive: Boolean
        get() = synchronized(this) { stereoLeft != null && stereoRight != null }

    /**
     * Processes incoming stereo frames by mapping them into native memory space.
     *
     * Synchronized because frames may arrive from more than one producer thread
     * (camera analysis / GL); without it the persistent direct buffers and their
     * size bookkeeping can be torn or null-unwrapped mid-allocation.
     *
     * @param left The left camera frame data.
     * @param right The right camera frame data.
     * @param width Frame width.
     * @param height Frame height.
     */
    @Synchronized
    fun processStereoFrames(left: ByteArray, right: ByteArray, width: Int, height: Int, timestamp: Long) {
        if (left.isEmpty() || right.isEmpty()) return
        val requiredSize = left.size

        if (currentAllocationSize != requiredSize || directLeftBuffer == null) {
            directLeftBuffer = ByteBuffer.allocateDirect(requiredSize)
            directRightBuffer = ByteBuffer.allocateDirect(right.size)
            currentAllocationSize = requiredSize
        }

        val leftBuf = directLeftBuffer ?: return
        val rightBuf = directRightBuffer ?: return

        leftBuf.clear(); leftBuf.put(left); leftBuf.rewind()
        rightBuf.clear(); rightBuf.put(right); rightBuf.rewind()

        slamManager.feedStereoData(leftBuf, rightBuf, width, height, timestamp)
    }

    /**
     * Accepts a single Y-plane frame (from CameraX ImageAnalysis) and pairs it with the
     * previous frame to form a temporal stereo pair. Skips on the very first call.
     *
     * @param yPlane The Y-plane ByteBuffer from ImageProxy.planes[0].buffer.
     * @param width  Frame width.
     * @param height Frame height.
     */
    @Synchronized
    fun submitFrame(yPlane: ByteBuffer, width: Int, height: Int, timestamp: Long) {
        val snapshot = yPlane.duplicate()  // independent position, shared backing data
        val frameSize = snapshot.remaining()

        if (stereoFrameSize != frameSize || stereoLeft == null) {
            val prev = ByteBuffer.allocateDirect(frameSize)
            stereoLeft = ByteBuffer.allocateDirect(frameSize)
            stereoRight = ByteBuffer.allocateDirect(frameSize)
            prevFrameBuffer = prev
            stereoFrameSize = frameSize
            prev.put(snapshot)
            prev.rewind()
            prevWidth = width
            prevHeight = height
            return
        }

        // After the allocation guard above these are always non-null on this thread
        // (state is mutated only under this lock), so capture locals and drop the !!.
        val prev = prevFrameBuffer ?: return
        val left = stereoLeft ?: return
        val right = stereoRight ?: return

        if (prevWidth != width || prevHeight != height) {
            prev.clear()
            prev.put(snapshot)
            prev.rewind()
            prevWidth = width
            prevHeight = height
            return
        }

        // Left = previous frame, Right = current frame
        left.clear()
        prev.rewind()
        left.put(prev)
        left.rewind()

        right.clear()
        right.put(snapshot)
        right.rewind()

        // Advance previous frame to current
        prev.clear()
        prev.put(right.duplicate().also { it.rewind() })
        prev.rewind()

        slamManager.feedStereoData(left, right, width, height, timestamp)
    }
}