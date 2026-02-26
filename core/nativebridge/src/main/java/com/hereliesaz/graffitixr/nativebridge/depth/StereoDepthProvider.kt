package com.hereliesaz.graffitixr.nativebridge.depth

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes dual-camera streams to generate depth information for AR occlusion.
 */
@Singleton
class StereoDepthProvider @Inject constructor(
    private val slamManager: SlamManager
) {

    /**
     * Passes raw stereo frame data to the native engine for disparity mapping.
     * * @param left The byte array from the left camera sensor.
     * @param right The byte array from the right camera sensor.
     * @param width Frame width in pixels.
     * @param height Frame height in pixels.
     */
    fun processFrames(left: ByteArray, right: ByteArray, width: Int, height: Int) {
        if (left.isNotEmpty() && right.isNotEmpty()) {
            slamManager.feedStereoData(left, right, width, height)
        }
    }
}