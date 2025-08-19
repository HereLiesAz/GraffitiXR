package com.hereliesaz.graffitixr.rendering

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun floatArrayToByteArray(data: FloatArray): ByteArray {
    val byteBuffer = ByteBuffer.allocate(data.size * 4)
    byteBuffer.order(ByteOrder.nativeOrder())
    val floatBuffer = byteBuffer.asFloatBuffer()
    floatBuffer.put(data)
    return byteBuffer.array()
}

fun shortArrayToByteArray(data: ShortArray): ByteArray {
    val byteBuffer = ByteBuffer.allocate(data.size * 2)
    byteBuffer.order(ByteOrder.nativeOrder())
    val shortBuffer = byteBuffer.asShortBuffer()
    shortBuffer.put(data)
    return byteBuffer.array()
}
