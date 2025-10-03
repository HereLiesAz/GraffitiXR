package com.hereliesaz.graffitixr.graphics

import android.opengl.GLES20
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a visual representation of an ARCore plane.
 */
class PlaneRenderer {
    private var program = 0
    private var positionAttrib = 0
    private var modelViewProjectionUniform = 0
    private var colorUniform = 0

    private var vertexBuffer: FloatBuffer? = null

    fun createOnGlThread() {
        val vertexShader =
            ShaderUtil.loadGLShader(TAG, VERTEX_SHADER, GLES20.GL_VERTEX_SHADER)
        val fragmentShader =
            ShaderUtil.loadGLShader(TAG, FRAGMENT_SHADER, GLES20.GL_FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glUseProgram(program)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    private fun updateVertexBuffer(plane: Plane) {
        val buffer = plane.polygon
        if (vertexBuffer == null || vertexBuffer!!.capacity() < buffer.remaining()) {
            val bb = ByteBuffer.allocateDirect(buffer.remaining())
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }
        vertexBuffer!!.rewind()
        vertexBuffer!!.put(buffer)
        vertexBuffer!!.rewind()
    }

    fun draw(plane: Plane, cameraPose: Pose, cameraProjection: FloatArray) {
        if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
            return
        }
        updateVertexBuffer(plane)

        GLES20.glUseProgram(program)

        val modelMatrix = FloatArray(16)
        plane.centerPose.toMatrix(modelMatrix, 0)

        // Get the camera's view matrix and then invert it.
        val viewMatrix = FloatArray(16)
        cameraPose.toMatrix(viewMatrix, 0)
        val invertedViewMatrix = FloatArray(16)
        android.opengl.Matrix.invertM(invertedViewMatrix, 0, viewMatrix, 0)

        val modelViewMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewMatrix, 0, invertedViewMatrix, 0, modelMatrix, 0)

        val modelViewProjectionMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewProjectionMatrix, 0, cameraProjection, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

        val color = when (plane.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> floatArrayOf(0f, 1f, 0f, 0.5f) // Green
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> floatArrayOf(1f, 0f, 0f, 0.5f) // Red
            Plane.Type.VERTICAL -> floatArrayOf(0f, 0f, 1f, 0.5f) // Blue
            else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f) // Gray
        }
        GLES20.glUniform4fv(colorUniform, 1, color, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexBuffer!!.limit() / 3)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(positionAttrib)
    }

    companion object {
        private val TAG = PlaneRenderer::class.java.simpleName
        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec3 a_Position;
            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
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