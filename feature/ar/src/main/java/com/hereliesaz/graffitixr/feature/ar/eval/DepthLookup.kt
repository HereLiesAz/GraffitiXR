package com.hereliesaz.graffitixr.feature.ar.eval

import java.nio.ByteBuffer

/** Reads a metric range from an ARCore 16-bit depth buffer at a normalized image coordinate. */
object DepthLookup {
    /**
     * @param u,v image-normalized [0,1] (e.g. from frame.transformCoordinates2d → IMAGE_NORMALIZED).
     * @return distance in meters, or -1f when the sample is missing/out of the valid 0..7900mm range.
     */
    fun depthMetersAt(buffer: ByteBuffer, stride: Int, depthW: Int, depthH: Int, u: Float, v: Float): Float =
        depthMetersAtPatch(buffer, stride, depthW, depthH, u, v, radius = 0)

    /**
     * Median of the valid depth samples in the (2*radius+1)² window centered on (u,v). ARCore depth
     * is noisy and pocked with holes per pixel/frame, so a single sample flickers wildly; the median
     * over a small patch is robust to outliers and dropouts. Returns meters, or -1f if no valid
     * sample (0 < mm < 7900) is found in the window. radius = 0 reproduces a single-pixel read.
     */
    fun depthMetersAtPatch(
        buffer: ByteBuffer, stride: Int, depthW: Int, depthH: Int, u: Float, v: Float, radius: Int
    ): Float {
        if (depthW <= 0 || depthH <= 0) return -1f
        val cx = (u.coerceIn(0f, 1f) * depthW).toInt().coerceIn(0, depthW - 1)
        val cy = (v.coerceIn(0f, 1f) * depthH).toInt().coerceIn(0, depthH - 1)
        val samples = ArrayList<Int>()
        for (dy in -radius..radius) {
            val y = cy + dy
            if (y < 0 || y >= depthH) continue
            for (dx in -radius..radius) {
                val x = cx + dx
                if (x < 0 || x >= depthW) continue
                val off = y * stride + x * 2
                if (off < 0 || off + 2 > buffer.limit()) continue
                val mm = buffer.getShort(off).toInt() and 0x1FFF
                if (mm in 1..7899) samples.add(mm)
            }
        }
        if (samples.isEmpty()) return -1f
        samples.sort()
        return samples[samples.size / 2] / 1000f
    }
}
