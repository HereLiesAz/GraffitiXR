package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

/**
 * Pixel-space camera calibration for a SphereSLAM frame.
 *
 * Distortion is currently assumed zero. The values must describe the same orientation and pixel
 * dimensions as the luma frames passed to the engine.
 */
data class SphereSlamCalibration(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
) {
    init {
        require(fx > 0f && fy > 0f)
        require(cx.isFinite() && cy.isFinite())
    }
}

/**
 * One planar KPM reference image.
 *
 * KPM uses referenceDpi to map pixels onto its planar coordinate system in millimetres. That means
 * the returned translation scale is only physically metric when referenceDpi is physically correct.
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
 * Result of calibrated planar KPM tracking.
 *
 * cameraFromPage3x4 is artoolkitX's row-major camera-from-reference-plane pose. Translation is in
 * KPM's reference-plane millimetres; do not treat it as an ARCore/OpenGL view matrix until the
 * coordinate-convention adapter is applied.
 */
data class PlanarMatch(
    val pageNo: Int,
    val cameraFromPage3x4: FloatArray,
    val reprojectionError: Float,
    val inlierCount: Int,
) {
    init {
        require(pageNo >= 0)
        require(cameraFromPage3x4.size == 12)
        require(inlierCount >= 0)
    }
}

/**
 * Native calibrated planar-tracker API shared by both runtime modes.
 *
 * In hybrid mode it produces relocalization observations beside ARCore. In standalone mode its
 * camera-from-wall result is converted into the primary wall-relative view matrix. ARCore itself is
 * never routed through this interface.
 */
interface SphereSlamEngine : AutoCloseable {
    val frameWidth: Int
    val frameHeight: Int
    val calibration: SphereSlamCalibration
    val isReady: Boolean

    fun addPage(luma: ByteBuffer, width: Int, height: Int, page: PlanarPage): Int
    fun match(luma: ByteBuffer): PlanarMatch?

    override fun close()
}
