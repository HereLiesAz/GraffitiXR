package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class PointCloudRenderer {
    private val vertexShaderCode =
        "uniform mat4 u_MvpMatrix;" +
        "attribute vec4 a_Position;" +
        "varying float v_Confidence;" +
        "void main() {" +
        "   gl_Position = u_MvpMatrix * vec4(a_Position.xyz, 1.0);" +
        "   gl_PointSize = 5.0;" +
        "   v_Confidence = a_Position.w;" +
        "}"

    private val fragmentShaderCode =
        "precision mediump float;" +
        "varying float v_Confidence;" +
        "void main() {" +
        "    gl_FragColor = vec4(1.0, 1.0, 0.0, v_Confidence);" +
        "}"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var vertexBuffer: FloatBuffer? = null

    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(it))
                GLES20.glDeleteProgram(it)
                program = 0
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Log.e(TAG, "Could not compile shader $type: " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
        }
    }

    fun draw(pointCloud: PointCloud, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (program == 0) return
        GLES20.glUseProgram(program)

        val points = pointCloud.points
        Log.d("PointCloudRenderer", "Number of points: " + points.remaining())
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

    companion object {
        private const val TAG = "PointCloudRenderer"
    }
}
