package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud

class PointCloudRenderer {
    private var vboId = 0
    private var programId = 0
    private var positionAttribute = 0
    private var colorUniform = 0
    private var modelViewProjectionUniform = 0
    private var numPoints = 0
    private var lastTimestamp: Long = 0

    fun createOnGlThread(context: Context) {
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

        val vertexShader = ShaderUtil.loadGLShader("shaders/vertex_shader.glsl", GLES20.GL_VERTEX_SHADER, context)
        val fragmentShader = ShaderUtil.loadGLShader("shaders/fragment_shader.glsl", GLES20.GL_FRAGMENT_SHADER, context)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        positionAttribute = GLES20.glGetAttribLocation(programId, "a_Position")
        colorUniform = GLES20.glGetUniformLocation(programId, "u_Color")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(programId, "u_ModelViewProjection")
    }

    fun update(pointCloud: PointCloud) {
        if (pointCloud.timestamp == lastTimestamp) {
            return
        }
        lastTimestamp = pointCloud.timestamp

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        numPoints = pointCloud.points.remaining() / 4
        if (numPoints > 0) {
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, numPoints * 16, pointCloud.points, GLES20.GL_DYNAMIC_DRAW)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (numPoints == 0) return

        GLES20.glUseProgram(programId)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(positionAttribute, 4, GLES20.GL_FLOAT, false, 16, 0)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)

        // Use the blue dot color
        GLES20.glUniform4f(colorUniform, 31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f)

        // REMOVED: GLES20.glPointSize(5.0f) -> Not supported in ES 2.0 Java API.
        // Handled in vertex_shader.glsl via gl_PointSize = X.0;

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}