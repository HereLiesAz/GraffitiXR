package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
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
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { shader ->
            GLES20.glShaderSource(shader, VERTEX_SHADER)
            GLES20.glCompileShader(shader)
        }
        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { shader ->
            GLES20.glShaderSource(shader, FRAGMENT_SHADER)
            GLES20.glCompileShader(shader)
        }

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")

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
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, verticesBuffer)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)

        val modelMatrix = FloatArray(16)
        pose.toMatrix(modelMatrix, 0)

        val scaleMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(scaleMatrix, 0)
        android.opengl.Matrix.scaleM(scaleMatrix, 0, width, 1.0f, height)

        val modelViewMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewMatrix, 0, modelMatrix, 0, scaleMatrix, 0)

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, indicesBuffer)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            uniform mat4 u_MvpMatrix;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
