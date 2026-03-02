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

    /**
     * Processes incoming stereo frames by mapping them into native memory space.
     *
     * @param left The left camera frame data.
     * @param right The right camera frame data.
     * @param width Frame width.
     * @param height Frame height.
     */
    fun processStereoFrames(left: ByteArray, right: ByteArray, width: Int, height: Int) {
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

        slamManager.feedStereoData(directLeftBuffer!!, directRightBuffer!!, width, height)
    }
}