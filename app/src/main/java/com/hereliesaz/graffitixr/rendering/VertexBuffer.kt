package com.hereliesaz.graffitixr.rendering

import java.io.Closeable

class VertexBuffer(buffer: GpuBuffer?, val numberOfEntries: Int) : Closeable {
    internal val buffer: GpuBuffer?
    var entries: Int

    init {
        require(buffer != null) { "buffer must not be null" }
        require(buffer.capacity() % (numberOfEntries * 4) == 0) { "buffer capacity must be a multiple of the entry size" }
        this.buffer = buffer
        entries = buffer.capacity() / (numberOfEntries * 4)
    }

    fun set(data: FloatArray?) {
        require(data != null) { "data must not be null" }
        val bytes = ByteArray(data.size * 4)
        val floatBuffer = java.nio.ByteBuffer.wrap(bytes).asFloatBuffer()
        floatBuffer.put(data)
        buffer?.set(bytes)
    }

    fun getNumberOfVertices(): Int {
        return entries
    }

    override fun close() {
        buffer?.free()
    }

    fun bind() {
        buffer?.bind()
    }
}
