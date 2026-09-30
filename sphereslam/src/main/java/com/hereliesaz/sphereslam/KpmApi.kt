package com.hereliesaz.sphereslam

import com.hereliesaz.graffitixr.nativebridge.KpmBridge
import java.nio.ByteBuffer

internal interface KpmApi {
    fun create(width: Int, height: Int): Long
    fun addPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int
    fun match(session: Long, luma: ByteBuffer, out: FloatArray): Int
    fun destroy(session: Long)
}

internal object NativeKpmApi : KpmApi {
    override fun create(width: Int, height: Int): Long =
        KpmBridge.createHomographySession(width, height)

    override fun addPage(
        session: Long,
        luma: ByteBuffer,
        width: Int,
        height: Int,
        referenceDpi: Float,
        pageNo: Int,
        imageNo: Int,
        maxFeatures: Int,
    ): Int = KpmBridge.addPlanarPage(
        session,
        luma,
        width,
        height,
        referenceDpi,
        pageNo,
        imageNo,
        maxFeatures,
    )

    override fun match(session: Long, luma: ByteBuffer, out: FloatArray): Int =
        KpmBridge.matchPlanar(session, luma, out)

    override fun destroy(session: Long) = KpmBridge.destroySession(session)
}
