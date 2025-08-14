package com.hereliesaz.MuralOverlay.rendering

import android.opengl.GLES30
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GpuBuffer(target: Int, size: Int, initialData: ByteArray?) : Closeable {
    private val target: Int
    private var sizeBytes: Int
    private val bufferId: Int

    init {
        this.target = target
        sizeBytes = size
        val bufferIdArray = IntArray(1)
        GLES30.glGenBuffers(1, bufferIdArray, 0)
        bufferId = bufferIdArray[0]
        if (initialData != null) {
            GLES30.glBindBuffer(target, bufferId)
            val byteBuffer = ByteBuffer.allocateDirect(initialData.size).order(ByteOrder.nativeOrder())
            byteBuffer.put(initialData)
            byteBuffer.position(0)
            GLES30.glBufferData(target, size, byteBuffer, GLES30.GL_STATIC_DRAW)
        }
    }

    fun set(data: ByteArray?) {
        require(data != null) { "data must not be null" }
        require(data.size <= sizeBytes) { "data size exceeds buffer size" }
        GLES30.glBindBuffer(target, bufferId)
        val byteBuffer = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        byteBuffer.put(data)
        byteBuffer.position(0)
        GLES30.glBufferSubData(target, 0, data.size, byteBuffer)
    }

    fun free() {
        if (bufferId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
        }
    }

    override fun close() {
        free()
    }

    fun capacity(): Int {
        return sizeBytes
    }

    fun bind() {
        GLES30.glBindBuffer(target, bufferId)
    }

    fun getBufferId(): Int {
        return bufferId
    }
}
