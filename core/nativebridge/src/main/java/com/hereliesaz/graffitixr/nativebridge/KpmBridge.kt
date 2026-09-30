package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import java.nio.ByteBuffer

/**
 * Low-level JNI bridge to the forked artoolkitX KPM tracker.
 *
 * This is intentionally a native primitive, not an ARCore replacement. :sphereslam owns the public
 * tracking API and feature/ar owns pose-source selection. ARCore continues to run through its own
 * ArCorePoseSource path.
 *
 * When third_party/artoolkitx is not checked out, the native side is compiled without HAVE_ARX_KPM
 * and every KPM entry point safely reports unavailable.
 */
object KpmBridge {
    init {
        NativeLibLoader.loadAll()
    }

    fun isAvailable(): Boolean = runCatching { nativeKpmAvailable() }.getOrDefault(false)

    fun smokeTest(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && nativeKpmSmokeTest(width, height)

    /**
     * Creates an independent homography-tracking session.
     *
     * The returned session does not contain camera calibration; its 3x4 result is a projective planar
     * transform, not a metric ARCore-style world pose.
     */
    fun createHomographySession(width: Int, height: Int): Long {
        require(width > 0 && height > 0)
        return nativeCreateHomographySession(width, height)
    }

    /**
     * Adds one planar reference image to the session's KPM atlas.
     *
     * luma must be a direct, tightly packed width*height luminance buffer.
     * Returns the number of generated reference features, or a negative error code.
     */
    fun addPlanarPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int {
        require(session != 0L)
        require(luma.isDirect)
        require(width > 0 && height > 0)
        require(referenceDpi > 0f)
        require(pageNo >= 0)
        require(imageNo >= 0)
        require(maxFeatures > 0)
        return nativeAddPlanarPage(
            session,
            luma,
            width,
            height,
            referenceDpi,
            pageNo,
            imageNo,
            maxFeatures,
        )
    }

    /**
     * Matches one tightly packed direct luma frame.
     *
     * On success returns pageNo and writes 14 floats to out:
     * 0..11 = KPM 3x4 projective transform, 12 = reprojection error, 13 = inlier count.
     * Returns -1 when there is no valid match.
     */
    fun matchPlanar(session: Long, luma: ByteBuffer, out: FloatArray): Int {
        require(session != 0L)
        require(luma.isDirect)
        require(out.size >= MATCH_OUTPUT_FLOATS)
        return nativeMatchPlanar(session, luma, out)
    }

    fun destroySession(session: Long) {
        if (session != 0L) nativeDestroySession(session)
    }

    private external fun nativeKpmAvailable(): Boolean
    private external fun nativeKpmSmokeTest(width: Int, height: Int): Boolean
    private external fun nativeCreateHomographySession(width: Int, height: Int): Long
    private external fun nativeAddPlanarPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int
    private external fun nativeMatchPlanar(session: Long, luma: ByteBuffer, out: FloatArray): Int
    private external fun nativeDestroySession(session: Long)

    const val MATCH_OUTPUT_FLOATS = 14
}
