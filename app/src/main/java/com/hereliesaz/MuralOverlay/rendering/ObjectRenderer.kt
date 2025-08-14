package com.hereliesaz.MuralOverlay.rendering

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ObjectRenderer(
    private val assets: AssetManager,
    private val vertexShaderFileName: String,
    private val fragmentShaderFileName: String,
    private val defines: Map<String, String>?
) : Closeable {

    private var shader: Shader? = null
    private var texture: Texture? = null
    private var mesh: Mesh? = null

    private var projectionMatrixLocation = 0
    private var viewMatrixLocation = 0
    private var modelMatrixLocation = 0
    private var opacityLocation = 0
    private var contrastLocation = 0
    private var saturationLocation = 0

    fun createOnGlThread() {
        shader = Shader(assets, vertexShaderFileName, fragmentShaderFileName, defines)
        texture = Texture(GLES30.GL_TEXTURE_2D, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_LINEAR, GLES30.GL_LINEAR)

        val quadCoords = floatArrayOf(
            -0.5f, -0.5f, 0.0f,
            -0.5f, +0.5f, 0.0f,
            +0.5f, -0.5f, 0.0f,
            +0.5f, +0.5f, 0.0f,
        )

        val quadTexCoords = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f,
        )

        val quadIndices = shortArrayOf(0, 1, 2, 2, 1, 3)

        val vertexBuffer = VertexBuffer(GpuBuffer(GLES30.GL_ARRAY_BUFFER, quadCoords.size * 4, floatArrayToByteArray(quadCoords)), 3)
        val texCoordBuffer = VertexBuffer(GpuBuffer(GLES30.GL_ARRAY_BUFFER, quadTexCoords.size * 4, floatArrayToByteArray(quadTexCoords)), 2)
        val indexBuffer = IndexBuffer(GpuBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, quadIndices.size * 2, shortArrayToByteArray(quadIndices)))

        mesh = Mesh(vertexBuffer, indexBuffer)

        shader?.let {
            projectionMatrixLocation = GLES30.glGetUniformLocation(it.getProgramId(), "u_Projection")
            viewMatrixLocation = GLES30.glGetUniformLocation(it.getProgramId(), "u_View")
            modelMatrixLocation = GLES30.glGetUniformLocation(it.getProgramId(), "u_Model")
            opacityLocation = GLES30.glGetUniformLocation(it.getProgramId(), "u_Opacity")
            contrastLocation = GLES30.glGetUniformLocation(it.getProgramId(), "u_Contrast")
            saturationLocation = GLES30.glGetUniformLocation(it.getProgramId(), "u_Saturation")
        }
    }

    fun draw(camera: Camera, anchor: Anchor, state: MuralState) {
        val modelMatrix = FloatArray(16)
        anchor.pose.toMatrix(modelMatrix, 0)

        shader?.use()
        shader?.let {
            GLES30.glUniformMatrix4fv(projectionMatrixLocation, 1, false, camera.projectionMatrix, 0)
            GLES30.glUniformMatrix4fv(viewMatrixLocation, 1, false, camera.viewMatrix, 0)
            GLES30.glUniformMatrix4fv(modelMatrixLocation, 1, false, modelMatrix, 0)
            GLES30.glUniform1f(opacityLocation, state.opacity)
            GLES30.glUniform1f(contrastLocation, state.contrast)
            GLES30.glUniform1f(saturationLocation, state.saturation)
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture!!.getTextureId())

        GLES30.glBindVertexArray(mesh!!.getVertexArrayId())
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh!!.indexBuffer.getSize(), GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun updateTexture(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture!!.getTextureId())
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }

    override fun close() {
        shader?.close()
        texture?.close()
        mesh?.close()
    }
}
