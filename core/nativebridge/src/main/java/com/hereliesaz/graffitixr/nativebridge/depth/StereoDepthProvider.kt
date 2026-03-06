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

    /**
     * Processes incoming stereo frames by mapping them into native memory space.
     *
     * @param left The left camera frame data.
     * @param right The right camera frame data.
     * @param width Frame width.
     * @param height Frame height.
     */
    fun processStereoFrames(left: ByteArray, right: ByteArray, width: Int, height: Int, timestamp: Long) {
        if (left.isEmpty() || right.isEmpty()) return
        val requiredSize = left.size

        if (currentAllocationSize != requiredSize || directLeftBuffer == null) {
            directLeftBuffer = ByteBuffer.allocateDirect(requiredSize)
            directRightBuffer = ByteBuffer.allocateDirect(right.size)
            currentAllocationSize = requiredSize
        }

        directLeftBuffer?.let {
            it.clear()
            it.put(left)
            it.rewind()
        }

        directRightBuffer?.let {
            it.clear()
            it.put(right)
            it.rewind()
        }

        slamManager.feedStereoData(directLeftBuffer!!, directRightBuffer!!, width, height, timestamp)
    }

    /**
     * Accepts a single Y-plane frame (from CameraX ImageAnalysis) and pairs it with the
     * previous frame to form a temporal stereo pair. Skips on the very first call.
     *
     * @param yPlane The Y-plane ByteBuffer from ImageProxy.planes[0].buffer.
     * @param width  Frame width.
     * @param height Frame height.
     */
    fun submitFrame(yPlane: ByteBuffer, width: Int, height: Int, timestamp: Long) {
        val snapshot = yPlane.duplicate()  // independent position, shared backing data
        val frameSize = snapshot.remaining()

        if (stereoFrameSize != frameSize || stereoLeft == null) {
            stereoLeft = ByteBuffer.allocateDirect(frameSize)
            stereoRight = ByteBuffer.allocateDirect(frameSize)
            prevFrameBuffer = ByteBuffer.allocateDirect(frameSize)
            stereoFrameSize = frameSize
            prevFrameBuffer!!.put(snapshot)
            prevFrameBuffer!!.rewind()
            prevWidth = width
            prevHeight = height
            return
        }

        if (prevWidth != width || prevHeight != height) {
            prevFrameBuffer!!.clear()
            prevFrameBuffer!!.put(snapshot)
            prevFrameBuffer!!.rewind()
            prevWidth = width
            prevHeight = height
            return
        }

        // Left = previous frame, Right = current frame
        stereoLeft!!.clear()
        prevFrameBuffer!!.rewind()
        stereoLeft!!.put(prevFrameBuffer!!)
        stereoLeft!!.rewind()

        stereoRight!!.clear()
        stereoRight!!.put(snapshot)
        stereoRight!!.rewind()

        // Advance previous frame to current
        prevFrameBuffer!!.clear()
        prevFrameBuffer!!.put(stereoRight!!.duplicate().also { it.rewind() })
        prevFrameBuffer!!.rewind()

        slamManager.feedStereoData(stereoLeft!!, stereoRight!!, width, height, timestamp)
    }
}