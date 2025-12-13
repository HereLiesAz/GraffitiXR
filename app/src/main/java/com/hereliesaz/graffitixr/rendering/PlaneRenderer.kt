package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders detected AR planes to provide visual feedback to the user.
 *
 * It visualizes the planes as semi-transparent polygons with a solid outline.
 * This helps the user understand where the AR system has detected surfaces.
 *
 * Key features:
 * - Renders a filled polygon (GL_TRIANGLE_FAN) for the plane surface.
 * - Renders a line loop (GL_LINE_LOOP) for the plane boundary.
 * - Handles GL blending for transparency.
 * - Converts 2D plane polygon vertices (X, Z) into 3D world coordinates (Y=0).
 */
class PlaneRenderer {
    private var planeProgram = 0
    private var positionAttribute = 0
    private var modelViewProjectionUniform = 0
    private var colorUniform = 0

    private var vertexBuffer: FloatBuffer? = null

    // Matrices
    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    /**
     * Initializes the OpenGL resources (shaders, program).
     * Must be called on the GL thread.
     */
    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        planeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(planeProgram, vertexShader)
        GLES20.glAttachShader(planeProgram, fragmentShader)
        GLES20.glLinkProgram(planeProgram)

        positionAttribute = GLES20.glGetAttribLocation(planeProgram, "a_Position")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(planeProgram, "u_Color")
    }

    /**
     * Updates the vertex buffer with the polygon data from the ARCore Plane.
     * Reallocates the buffer if the polygon grows larger than the current capacity.
     */
    private fun updatePlaneData(plane: Plane) {
        val planePolygon = plane.polygon
        planePolygon.rewind()

        if (vertexBuffer == null || vertexBuffer!!.capacity() < planePolygon.limit()) {
            val bb = ByteBuffer.allocateDirect(planePolygon.limit() * 4)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }

        vertexBuffer!!.clear()
        vertexBuffer!!.put(planePolygon)
        vertexBuffer!!.flip()
    }

    /**
     * Draws a single AR plane.
     *
     * @param plane The ARCore plane to draw.
     * @param viewMatrix The camera's view matrix.
     * @param projectionMatrix The camera's projection matrix.
     */
    fun draw(plane: Plane, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
            return
        }

        updatePlaneData(plane)

        GLES20.glUseProgram(planeProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false) // Don't write to depth buffer for transparent planes

        // 1. Calculate MVP Matrix
        plane.centerPose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        // 2. Set Matrix Uniform
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

        // 3. Set Attribute
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttribute)

        // 4. Draw Fill (Semi-transparent Cyan)
        GLES20.glUniform4f(colorUniform, 0.0f, 1.0f, 1.0f, 0.2f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, plane.polygon.limit() / 2)

        // 5. Draw Outline (Solid Cyan)
        GLES20.glLineWidth(5.0f) // Thicker lines for visibility
        GLES20.glUniform4f(colorUniform, 0.0f, 1.0f, 1.0f, 1.0f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, plane.polygon.limit() / 2)

        // Cleanup
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        // Simple Vertex Shader
        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec2 a_Position;
            void main() {
               // ARCore plane vertices are X, Z. Y is 0.
               gl_Position = u_ModelViewProjection * vec4(a_Position.x, 0.0, a_Position.y, 1.0);
            }
        """

        // Simple Fragment Shader (Solid Color)
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
