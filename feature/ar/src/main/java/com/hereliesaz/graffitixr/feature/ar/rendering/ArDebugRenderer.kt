// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/rendering/ArDebugRenderer.kt
package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import com.hereliesaz.graffitixr.common.util.GlReleasable
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil

/**
 * Diagnostic visualization of what ARCore is currently perceiving: the CURRENT frame's sparse
 * feature points, drawn as yellow dots over the camera passthrough. Deliberately separate from
 * [PointCloudRenderer]: that one ACCUMULATES points and persists them with the project
 * (saveCloudPoints), so feeding it every frame for a debug view would pollute the saved cloud.
 * This renderer holds only the latest frame's points and owns no persistent state.
 *
 * Driven by [ArRenderer.showArDebugView], which MainScreen ties to the existing Diagnostic
 * Overlay setting. Tracked planes are visualized separately via [PlaneRenderer.drawPlanes];
 * together they show "what the AR is seeing" — feature points + fitted surfaces.
 *
 * GL-thread only, like the other sub-renderers.
 */
class ArDebugRenderer : GlReleasable {
    private val tag = "ArDebugRenderer"

    private var program = 0
    private var positionHandle = 0
    private var mvpMatrixHandle = 0
    private var pointSizeHandle = 0
    private var vboId = 0

    private val maxPoints = 20000
    private var pointCount = 0
    // Last point-cloud timestamp uploaded; ARCore returns the same cloud object until a new one
    // is computed, so skipping identical timestamps avoids redundant VBO uploads.
    private var lastCloudTimestampNs = 0L

    fun createOnGlThread(context: Context) {
        val vertexShaderCode = """
            uniform mat4 u_MvpMatrix;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(1.0, 0.9, 0.1, 0.9);
            }
        """.trimIndent()

        val vertexShader = ShaderUtil.loadGLShader(tag, context, GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = ShaderUtil.loadGLShader(tag, context, GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        pointSizeHandle = GLES20.glGetUniformLocation(program, "u_PointSize")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, maxPoints * 4 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Uploads the current frame's point cloud. Replaces — never accumulates. GL thread only. */
    fun update(pointCloud: PointCloud) {
        if (pointCloud.timestamp == lastCloudTimestampNs) return
        lastCloudTimestampNs = pointCloud.timestamp

        val points = pointCloud.points // FloatBuffer of (x, y, z, confidence)
        val floats = points.remaining()
        pointCount = (floats / 4).coerceAtMost(maxPoints)
        if (pointCount == 0) return

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, pointCount * 4 * 4, points)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Draws the latest points on top of the scene (depth test off so nothing occludes them). */
    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (pointCount == 0 || program == 0) return

        GLES20.glUseProgram(program)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointSizeHandle, 9.0f)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    override fun release() {
        if (vboId != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0)
            vboId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        pointCount = 0
        lastCloudTimestampNs = 0L
    }
}
