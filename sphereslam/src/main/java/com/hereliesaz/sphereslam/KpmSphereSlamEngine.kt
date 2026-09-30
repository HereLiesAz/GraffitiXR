package com.hereliesaz.sphereslam

import java.nio.ByteBuffer

internal class KpmSphereSlamEngine(
    override val frameWidth: Int,
    override val frameHeight: Int,
    private val api: KpmApi,
) : SphereSlamEngine {
    private var session: Long = api.create(frameWidth, frameHeight)
    private val scratch = FloatArray(14)

    override val isReady: Boolean
        get() = session != 0L

    override fun addPage(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        page: PlanarPage,
    ): Int {
        val active = requireOpen()
        val packed = packedView(luma, width, height)
        val result = api.addPage(
            active,
            packed,
            width,
            height,
            page.referenceDpi,
            page.pageNo,
            page.imageNo,
            page.maxFeatures,
        )
        check(result >= 0) { "KPM failed to add planar page: error $result" }
        return result
    }

    override fun match(luma: ByteBuffer): PlanarMatch? {
        val active = requireOpen()
        val packed = packedView(luma, frameWidth, frameHeight)
        val pageNo = api.match(active, packed, scratch)
        if (pageNo < 0) return null

        return PlanarMatch(
            pageNo = pageNo,
            projectiveTransform3x4 = scratch.copyOfRange(0, 12),
            reprojectionError = scratch[12],
            inlierCount = scratch[13].toInt().coerceAtLeast(0),
        )
    }

    override fun close() {
        val active = session
        if (active == 0L) return
        session = 0L
        api.destroy(active)
    }

    private fun requireOpen(): Long {
        check(session != 0L) { "SphereSLAM session is unavailable or already closed" }
        return session
    }

    private fun packedView(buffer: ByteBuffer, width: Int, height: Int): ByteBuffer {
        require(width > 0 && height > 0)
        require(buffer.isDirect) { "KPM requires a direct luminance ByteBuffer" }
        val required = width.toLong() * height.toLong()
        require(required <= Int.MAX_VALUE)
        require(buffer.remaining() >= required.toInt()) {
            "Luma buffer has ${buffer.remaining()} bytes; need $required"
        }
        return buffer.slice().apply { limit(required.toInt()) }
    }
}
