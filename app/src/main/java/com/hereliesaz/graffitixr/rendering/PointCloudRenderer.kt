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
        "   gl_PointSize = 15.0;" +
        "   v_Confidence = a_Position.w;" +
        "}"

    private val fragmentShaderCode =
        "precision mediump float;" +
        "varying float v_Confidence;" +
        "void main() {" +
        "    vec2 coord = gl_PointCoord - vec2(0.5);" +
        "    if (length(coord) > 0.5) {" +
        "        discard;" +
        "    }" +
        "    gl_FragColor = vec4(0.0, 1.0, 1.0, 1.0);" +
        "}"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var vertexBuffer: FloatBuffer? = null

    // Pre-allocate a small buffer to start with
    private var vertexBufferSize = 1000 * 4 * 4 // 1000 points

    init {
        updateVertexBuffer(1000)
    }

    private fun updateVertexBuffer(numPoints: Int) {
        if (vertexBuffer == null || vertexBuffer!!.capacity() < numPoints * 4) {
            val bb = ByteBuffer.allocateDirect(numPoints * 4 * 4) // 4 floats per point, 4 bytes per float
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
            vertexBufferSize = numPoints * 4 * 4
        }
    }

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
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "Could not create shader of type $type")
            return 0
        }
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e(TAG, "Could not compile shader $type: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    fun draw(pointCloud: PointCloud, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (program == 0) {
            Log.w(TAG, "Program is 0, skipping draw")
            return
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glUseProgram(program)

        val points = pointCloud.points
        val numPoints = points.remaining() / 4
        Log.d(TAG, "Drawing $numPoints points")

        if (numPoints == 0) {
            return
        }

        updateVertexBuffer(numPoints)
        vertexBuffer?.let { buffer ->
            buffer.clear()
            buffer.put(points)
            buffer.position(0)

            positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, buffer)
        }

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

        GLES20.glDisableVertexAttribArray(positionHandle)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)

        // Check for GL errors
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL Error in draw: $error")
        }
    }

    companion object {
        private const val TAG = "PointCloudRenderer"
    }
}
