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
    private var gridControlUniform = 0 // x=thickness, y=alpha

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
        gridControlUniform = GLES20.glGetUniformLocation(planeProgram, "u_GridControl")
    }

    /**
     * Updates the vertex buffer with the polygon data from the ARCore Plane.
     * Reallocates the buffer if the polygon grows larger than the current capacity.
     */
    private fun updatePlaneData(plane: Plane) {
        val planePolygon = plane.polygon
        planePolygon.rewind()

        val requiredCapacity = planePolygon.limit()

        if (vertexBuffer == null || vertexBuffer!!.capacity() < requiredCapacity) {
            // Bolt Optimization: Grow buffer geometrically to avoid frequent re-allocations as plane grows
            val newCapacity = if (vertexBuffer != null) {
                maxOf(requiredCapacity, vertexBuffer!!.capacity() * 2)
            } else {
                requiredCapacity
            }

            val bb = ByteBuffer.allocateDirect(newCapacity * 4)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }

        vertexBuffer!!.clear()
        vertexBuffer!!.put(planePolygon)
        vertexBuffer!!.flip()
    }

    /**
     * Draws a collection of AR planes.
     *
     * @param planes The collection of ARCore planes to draw.
     * @param viewMatrix The camera's view matrix.
     * @param projectionMatrix The camera's projection matrix.
     * @param gridAlpha Opacity multiplier for the internal grid fill.
     * @param gridLineWidth Thickness of the internal grid lines.
     * @param outlineWidth Thickness of the outer boundary line.
     * @return True if at least one plane was drawn.
     */
    fun drawPlanes(
        planes: Collection<Plane>,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        gridAlpha: Float = 1.0f,
        gridLineWidth: Float = 0.02f,
        outlineWidth: Float = 5.0f
    ): Boolean {
        var hasDrawn = false
        var isStateSet = false

        // Calculate Camera Forward Vector in World Space
        // ViewMatrix maps World -> Camera.
        // Row 2 (indices 2, 6, 10) corresponds to the Camera's "Back" vector in World Space.
        // So Forward is negation of that.
        val camFwdX = -viewMatrix[2]
        val camFwdY = -viewMatrix[6]
        val camFwdZ = -viewMatrix[10]

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }

            if (!isStateSet) {
                // Bolt Optimization: Set GL state once for all planes
                GLES20.glUseProgram(planeProgram)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false) // Don't write to depth buffer for transparent planes
                isStateSet = true
            }

            updatePlaneData(plane)

            // 1. Calculate MVP Matrix
            plane.centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

            // Calculate Plane Normal in World Space
            // Y-axis of the ModelMatrix (Column 1: indices 4, 5, 6) is the Plane Normal.
            val normX = modelMatrix[4]
            val normY = modelMatrix[5]
            val normZ = modelMatrix[6]

            // Calculate Angle of Incidence (Dot Product)
            val dot = camFwdX * normX + camFwdY * normY + camFwdZ * normZ
            val absDot = kotlin.math.abs(dot)

            // Determine Color based on orientation
            // Green: Surface is parallel to the device screen (Face-on view).
            // Pink: Surface is orthogonal to the device orientation (e.g. Floor when holding device vertically).
            // Cyan: Surface matches device orientation but is not parallel (e.g. Side Wall when holding device vertically).
            val (r, g, b) = if (absDot > 0.7f) {
                Triple(0.0f, 1.0f, 0.0f) // Bright Green
            } else {
                // Check verticality of View and Normal
                // 0.707 is approx sin(45 degrees)
                val viewIsVertical = kotlin.math.abs(camFwdY) > 0.707f
                val normIsVertical = kotlin.math.abs(normY) > 0.707f // Normal is Vertical means Plane is Horizontal

                if (viewIsVertical != normIsVertical) {
                    Triple(1.0f, 0.0f, 1.0f) // Bright Pink (Mismatch)
                } else {
                    Triple(0.0f, 1.0f, 1.0f) // Cyan (Match but not parallel)
                }
            }

            // 2. Set Matrix Uniform
            GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

            // 3. Set Attribute
            GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionAttribute)

            // 4. Draw Fill (Grid Pattern)
            if (gridAlpha > 0.0f) {
                GLES20.glUniform2f(gridControlUniform, gridLineWidth, gridAlpha)
                GLES20.glUniform4f(colorUniform, r, g, b, 0.0f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, plane.polygon.limit() / 2)
            }

            // 5. Draw Outline (Solid Color)
            if (outlineWidth > 0.0f) {
                GLES20.glLineWidth(outlineWidth)
                GLES20.glUniform4f(colorUniform, r, g, b, 1.0f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, plane.polygon.limit() / 2)
            }

            GLES20.glDisableVertexAttribArray(positionAttribute)

            // We consider the plane "processed" (detected) even if we didn't draw it due to visibility settings.
            // This ensures the UI knows planes exist.
            hasDrawn = true
        }

        if (isStateSet) {
            // Cleanup
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        return hasDrawn
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        // Grid Vertex Shader
        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec2 a_Position;
            varying vec2 v_TexCoord;
            void main() {
               // ARCore plane vertices are X, Z. Y is 0.
               v_TexCoord = a_Position;
               gl_Position = u_ModelViewProjection * vec4(a_Position.x, 0.0, a_Position.y, 1.0);
            }
        """

        // Grid Fragment Shader
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            uniform vec2 u_GridControl; // x = thickness, y = alpha
            varying vec2 v_TexCoord;
            void main() {
                // Grid logic
                float gridWidth = 0.1524; // Width of grid cells in meters (approx 6 inches)
                float lineThickness = u_GridControl.x;

                // Calculate grid lines
                vec2 grid = step(gridWidth - lineThickness, mod(abs(v_TexCoord), gridWidth));
                float isLine = max(grid.x, grid.y);

                // Mix alpha: Higher alpha for lines, lower for fill
                // u_Color.a is passed as 0.0 for fill usually, so mix(0.0, 1.0, isLine) = isLine (1.0 or 0.0)
                // Then we scale by global alpha (u_GridControl.y)

                float alpha = mix(u_Color.a, 1.0, isLine);
                alpha *= u_GridControl.y;

                gl_FragColor = vec4(u_Color.rgb, alpha);
            }
        """
    }
}
