package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

/**
 * One planar KPM reference image.
 *
 * referenceDpi controls the reference coordinate scale inside KPM. Until calibrated camera
 * intrinsics + a physical wall scale are supplied, it must not be interpreted as real-world metres.
 */
data class PlanarPage(
    val pageNo: Int,
    val imageNo: Int = 0,
    val referenceDpi: Float = 72f,
    val maxFeatures: Int = 5000,
) {
    init {
        require(pageNo >= 0)
        require(imageNo >= 0)
        require(referenceDpi > 0f)
        require(maxFeatures > 0)
    }
}

/**
 * Result of planar KPM tracking.
 *
 * projectiveTransform3x4 is KPM homography-mode output in row-major order. It is deliberately not
 * called a camera pose: without camera calibration it is not equivalent to ARCore's metric 6-DoF
 * world-to-view matrix.
 */
data class PlanarMatch(
    val pageNo: Int,
    val projectiveTransform3x4: FloatArray,
    val reprojectionError: Float,
    val inlierCount: Int,
) {
    init {
        require(pageNo >= 0)
        require(projectiveTransform3x4.size == 12)
        require(inlierCount >= 0)
    }
}

/**
 * Side-by-side native tracker API. ARCore is not routed through this interface and is not replaced.
 */
interface SphereSlamEngine : AutoCloseable {
    val frameWidth: Int
    val frameHeight: Int
    val isReady: Boolean

    fun addPage(luma: ByteBuffer, width: Int, height: Int, page: PlanarPage): Int
    fun match(luma: ByteBuffer): PlanarMatch?

    override fun close()
}
