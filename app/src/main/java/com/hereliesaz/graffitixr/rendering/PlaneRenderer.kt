package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Plane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class PlaneRenderer {
    private val vertexShaderCode =
        "uniform mat4 u_MvpMatrix;" +
        "attribute vec2 a_Position;" +
        "varying vec2 v_GridCoord;" +
        "void main() {" +
        "   gl_Position = u_MvpMatrix * vec4(a_Position.x, 0.0, a_Position.y, 1.0);" +
        "   v_GridCoord = a_Position;" +
        "}"

    private val fragmentShaderCode =
        "precision mediump float;" +
        "varying vec2 v_GridCoord;" +
        "void main() {" +
        "    float grid_spacing = 0.1;" +
        "    vec2 grid_coord = fract(v_GridCoord / grid_spacing);" +
        "    float line_width = 0.02;" +
        "    if (grid_coord.x < line_width || grid_coord.x > 1.0 - line_width ||" +
        "        grid_coord.y < line_width || grid_coord.y > 1.0 - line_width) {" +
        "        gl_FragColor = vec4(1.0, 1.0, 1.0, 0.5);" +
        "    } else {" +
        "        gl_FragColor = vec4(0.2, 0.4, 1.0, 0.2);" +
        "    }" +
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

    fun draw(plane: Plane, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (program == 0) {
            Log.e(TAG, "draw: Program is 0")
            return
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(program)

        val polygon = plane.polygon
        if (polygon.remaining() == 0) {
            Log.v(TAG, "draw: Plane polygon empty")
            return
        }

        vertexBuffer = ByteBuffer.allocateDirect(polygon.remaining() * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(polygon)
                position(0)
            }
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position").also {
            GLES20.glEnableVertexAttribArray(it)
            GLES20.glVertexAttribPointer(it, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        }

        val modelMatrix = FloatArray(16)
        plane.centerPose.toMatrix(modelMatrix, 0)

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix").also {
            GLES20.glUniformMatrix4fv(it, 1, false, mvpMatrix, 0)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, polygon.remaining() / 2)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisable(GLES20.GL_BLEND)

        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL Error in PlaneRenderer.draw: $error")
        }
    }

    companion object {
        private const val TAG = "PlaneRenderer"
    }
}
