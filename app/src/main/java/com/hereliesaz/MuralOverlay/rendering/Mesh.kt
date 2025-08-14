package com.hereliesaz.MuralOverlay.rendering

import android.opengl.GLES30
import java.io.Closeable

class Mesh(
    val vertexBuffer: VertexBuffer,
    val indexBuffer: IndexBuffer,
    val texCoordBuffer: VertexBuffer,
    var depthTexCoordBuffer: VertexBuffer? = null,
    val primitiveMode: Int = GLES30.GL_TRIANGLES
) : Closeable {

    private val vertexArrayId: Int

    init {
        val vertexArrayIdArray = IntArray(1)
        GLES30.glGenVertexArrays(1, vertexArrayIdArray, 0)
        vertexArrayId = vertexArrayIdArray[0]
        GLES30.glBindVertexArray(vertexArrayId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer.buffer!!.getBufferId())
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, texCoordBuffer.buffer!!.getBufferId())
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(1)

        depthTexCoordBuffer?.let {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, it.buffer!!.getBufferId())
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glEnableVertexAttribArray(2)
        }

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.buffer!!.getBufferId())
    }

    fun getVertexArrayId(): Int {
        return vertexArrayId
    }

    override fun close() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
    }
}
