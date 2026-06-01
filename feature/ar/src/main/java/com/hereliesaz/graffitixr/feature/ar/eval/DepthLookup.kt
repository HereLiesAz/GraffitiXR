package com.hereliesaz.graffitixr.feature.ar.eval

import java.nio.ByteBuffer

/** Reads a metric range from an ARCore 16-bit depth buffer at a normalized image coordinate. */
object DepthLookup {
    /**
     * @param u,v image-normalized [0,1] (e.g. from frame.transformCoordinates2d → IMAGE_NORMALIZED).
     * @return distance in meters, or -1f when the sample is missing/out of the valid 0..7900mm range.
     */
    fun depthMetersAt(buffer: ByteBuffer, stride: Int, depthW: Int, depthH: Int, u: Float, v: Float): Float {
        if (depthW <= 0 || depthH <= 0) return -1f
        val x = (u.coerceIn(0f, 1f) * depthW).toInt().coerceIn(0, depthW - 1)
        val y = (v.coerceIn(0f, 1f) * depthH).toInt().coerceIn(0, depthH - 1)
        val off = y * stride + x * 2
        if (off < 0 || off + 2 > buffer.limit()) return -1f
        val raw = buffer.getShort(off).toInt() and 0xFFFF
        val mm = raw and 0x1FFF
        return if (mm in 1..7899) mm / 1000f else -1f
    }
}
