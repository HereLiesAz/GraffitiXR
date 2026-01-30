package com.hereliesaz.graffitixr.feature.ar

import android.opengl.GLES30
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class AugmentedImageRenderer {
    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpMatrixHandle = 0

    private val vertices = floatArrayOf(
        -0.5f, -0.5f, 0.0f,
        0.5f, -0.5f, 0.0f,
        0.5f, 0.5f, 0.0f,
        -0.5f, 0.5f, 0.0f
    )
    private val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

    private var verticesBuffer: FloatBuffer? = null
    private var indicesBuffer: java.nio.ShortBuffer? = null

    private val color = floatArrayOf(0.0f, 1.0f, 0.0f, 0.5f)

    fun createOnGlThread() {
        val vertexShader = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER).also { shader ->
            GLES30.glShaderSource(shader, VERTEX_SHADER)
            GLES30.glCompileShader(shader)
        }
        val fragmentShader = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER).also { shader ->
            GLES30.glShaderSource(shader, FRAGMENT_SHADER)
            GLES30.glCompileShader(shader)
        }

        program = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)
        }

        positionHandle = GLES30.glGetAttribLocation(program, "a_Position")
        colorHandle = GLES30.glGetUniformLocation(program, "u_Color")
        mvpMatrixHandle = GLES30.glGetUniformLocation(program, "u_MvpMatrix")

        val bbVertices = ByteBuffer.allocateDirect(vertices.size * 4)
        bbVertices.order(ByteOrder.nativeOrder())
        verticesBuffer = bbVertices.asFloatBuffer()
        verticesBuffer?.put(vertices)
        verticesBuffer?.position(0)

        val bbIndices = ByteBuffer.allocateDirect(indices.size * 2)
        bbIndices.order(ByteOrder.nativeOrder())
        indicesBuffer = bbIndices.asShortBuffer()
        indicesBuffer?.put(indices)
        indicesBuffer?.position(0)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, pose: Pose, width: Float, height: Float) {
        GLES30.glUseProgram(program)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 0, verticesBuffer)
        GLES30.glUniform4fv(colorHandle, 1, color, 0)

        val modelMatrix = FloatArray(16)
        pose.toMatrix(modelMatrix, 0)

        val scaleMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(scaleMatrix, 0)
        android.opengl.Matrix.scaleM(scaleMatrix, 0, width, 1.0f, height)

        val modelXScale = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelXScale, 0, modelMatrix, 0, scaleMatrix, 0)

        val modelViewMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelXScale, 0)

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indices.size, GLES30.GL_UNSIGNED_SHORT, indicesBuffer)
        GLES30.glDisableVertexAttribArray(positionHandle)
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            in vec4 a_Position;
            uniform mat4 u_MvpMatrix;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            uniform vec4 u_Color;
            out vec4 FragColor;
            void main() {
                FragColor = u_Color;
            }
        """
    }
}
