package com.hereliesaz.graffitixr.graphics

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import java.nio.FloatBuffer

/**
 * Renders a point cloud.
 */
class PointCloudRenderer {
    private var vbo = 0
    private var vboSize = 0
    private var program = 0
    private var positionAttrib = 0
    private var modelViewProjectionUniform = 0
    private var colorUniform = 0
    private var pointSizeUniform = 0
    private var numPoints = 0

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
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")

        val vbos = IntArray(1)
        GLES20.glGenBuffers(1, vbos, 0)
        vbo = vbos[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        vboSize = 0
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vboSize, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Updates the OpenGL buffer contents with the provided point cloud data.
     */
    fun update(pointCloud: PointCloud) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        numPoints = pointCloud.points.remaining() / 4
        if (vboSize < pointCloud.points.remaining()) {
            vboSize = pointCloud.points.remaining()
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vboSize, pointCloud.points, GLES20.GL_DYNAMIC_DRAW)
        } else {
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, pointCloud.points.remaining(), pointCloud.points)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Renders the point cloud.
     */
    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val modelViewProjectionMatrix = FloatArray(16)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glVertexAttribPointer(positionAttrib, 4, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glUniform4f(colorUniform, 1.0f, 1.0f, 1.0f, 1.0f)
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
        GLES20.glUniform1f(pointSizeUniform, 10.0f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private val TAG = PointCloudRenderer::class.java.simpleName
        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_PointSize = u_PointSize;
                gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
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