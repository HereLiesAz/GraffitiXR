package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import com.google.ar.core.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class PointCloudRenderer {
    private val vertexShaderCode =
        "uniform mat4 u_MvpMatrix;" +
        "attribute vec4 a_Position;" +
        "void main() {" +
        "   gl_Position = u_MvpMatrix * a_Position;" +
        "   gl_PointSize = 10.0;" +
        "}"

    private val fragmentShaderCode =
        "void main() {" +
        "    gl_FragColor = vec4(1.0, 1.0, 0.0, 1.0);" +
        "}"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var vertexBuffer: FloatBuffer? = null

    fun createOnGlThread() {
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { shader ->
            GLES20.glShaderSource(shader, vertexShaderCode)
            GLES20.glCompileShader(shader)
        }

        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { shader ->
            GLES20.glShaderSource(shader, fragmentShaderCode)
            GLES20.glCompileShader(shader)
        }

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    fun draw(pointCloud: PointCloud, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        GLES20.glUseProgram(program)

        val points = pointCloud.points
        if (points.remaining() == 0) {
            return
        }

        vertexBuffer = ByteBuffer.allocateDirect(points.remaining() * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(points)
                position(0)
            }
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position").also {
            GLES20.glEnableVertexAttribArray(it)
            GLES20.glVertexAttribPointer(it, 4, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        }

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix").also {
            GLES20.glUniformMatrix4fv(it, 1, false, mvpMatrix, 0)
        }

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.remaining() / 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }
}
