package com.hereliesaz.graffitixr.graphics

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
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
    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

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
        val polygon = plane.polygon
        if (vertexBuffer == null || vertexBuffer!!.capacity() < polygon.remaining()) {
            val bb = ByteBuffer.allocateDirect(polygon.remaining() * 4)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }
        vertexBuffer!!.rewind()
        vertexBuffer!!.put(polygon)
        vertexBuffer!!.position(0)
    }

    fun draw(plane: Plane, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
            return
        }

        updateVertexBuffer(plane)
        GLES20.glUseProgram(program)

        // Set up the model-view-projection matrix for the plane.
        plane.centerPose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

        // Set the color of the plane.
        val color = when (plane.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> floatArrayOf(0.0f, 1.0f, 0.0f, 0.5f) // Green
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> floatArrayOf(1.0f, 0.0f, 0.0f, 0.5f) // Red
            Plane.Type.VERTICAL -> floatArrayOf(0.0f, 0.0f, 1.0f, 0.5f) // Blue
            else -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f) // Gray
        }
        GLES20.glUniform4fv(colorUniform, 1, color, 0)

        // Draw the plane.
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