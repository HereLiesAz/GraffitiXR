package com.hereliesaz.MuralOverlay.rendering

import android.opengl.GLES30
import java.io.Closeable

class IndexBuffer(buffer: GpuBuffer?) : Closeable {
    internal val buffer: GpuBuffer?
    var size = 0

    init {
        require(buffer != null) { "buffer must not be null" }
        require(buffer.capacity() % 2 == 0) { "buffer capacity must be a multiple of 2" }
        this.buffer = buffer
        size = buffer.capacity() / 2
    }

    fun set(data: ShortArray?) {
        require(data != null) { "data must not be null" }
        val bytes = ByteArray(data.size * 2)
        val shortBuffer = java.nio.ByteBuffer.wrap(bytes).asShortBuffer()
        shortBuffer.put(data)
        buffer?.set(bytes)
    }

    override fun close() {
        buffer?.free()
    }

    fun bind() {
        buffer?.bind()
    }
}
