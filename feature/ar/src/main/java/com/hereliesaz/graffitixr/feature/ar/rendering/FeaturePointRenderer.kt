package com.hereliesaz.graffitixr.feature.ar.rendering

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.PointCloud

/**
 * Renders ARCore's native tracking feature points as small cyan circles
 * drawn over the camera feed. Confidence (w-component) drives alpha so
 * high-quality points appear solid and low-quality points are translucent.
 *
 * Points are in world space; caller supplies the view and projection matrices
 * that are already computed in the tracking frame.
 */
class FeaturePointRenderer {

    private var program: Int = 0
    private var positionAttrib: Int = 0
    private var mvpUniform: Int = 0

    fun createOnGlThread() {
        val vert = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val frag = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vert == 0 || frag == 0) return

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vert)
        GLES30.glAttachShader(program, frag)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Link error: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            program = 0
            return
        }

        positionAttrib = GLES30.glGetAttribLocation(program, "a_Position")
        mvpUniform     = GLES30.glGetUniformLocation(program, "u_MVP")
    }

    /** Draw [pointCloud] using the supplied view and projection matrices. */
    fun draw(pointCloud: PointCloud, viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (program == 0) return

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        val points = pointCloud.points
        val numPoints = points.remaining() / 4   // each entry: x, y, z, confidence
        if (numPoints == 0) return

        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        points.position(0)
        // Stride 0 — tightly packed; each vec4 = x, y, z, confidence.
        GLES30.glVertexAttribPointer(positionAttrib, 4, GLES30.GL_FLOAT, false, 0, points)
        GLES30.glEnableVertexAttribArray(positionAttrib)

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, numPoints)

        GLES30.glDisableVertexAttribArray(positionAttrib)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Compile error: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "FeaturePointRenderer"

        private val VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec4 a_Position;
            uniform mat4 u_MVP;
            out float v_Confidence;
            void main() {
                gl_Position  = u_MVP * vec4(a_Position.xyz, 1.0);
                gl_PointSize = 8.0;
                v_Confidence = a_Position.w;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in float v_Confidence;
            out vec4 fragColor;
            void main() {
                // Clip to a circular point shape.
                vec2 c = gl_PointCoord - 0.5;
                if (dot(c, c) > 0.25) discard;
                // Cyan, brighter for high-confidence points.
                float alpha = 0.4 + 0.6 * v_Confidence;
                fragColor = vec4(0.0, 0.95, 0.85, alpha);
            }
        """.trimIndent()
    }
}
