package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import java.nio.FloatBuffer

class PointCloudRenderer {
    private var vboId = 0
    private var programId = 0
    private var positionHandle = 0
    private var mvpMatrixHandle = 0
    private var pointSizeHandle = 0
    private var numPoints = 0

    fun createOnGlThread(context: Context) {
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

        val vertexShader = ShaderUtil.loadGLShader("shaders/point_cloud_vertex.glsl", GLES20.GL_VERTEX_SHADER, context)
        val fragmentShader = ShaderUtil.loadGLShader("shaders/point_cloud_fragment.glsl", GLES20.GL_FRAGMENT_SHADER, context)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        positionHandle = GLES20.glGetAttribLocation(programId, "a_Position")
        mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "u_ModelViewProjection")
        pointSizeHandle = GLES20.glGetUniformLocation(programId, "u_PointSize")
    }

    fun update(pointCloud: PointCloud) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        numPoints = pointCloud.points.remaining() / 4
        if (numPoints > 0) {
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                numPoints * 16,
                pointCloud.points,
                GLES20.GL_DYNAMIC_DRAW
            )
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (numPoints == 0) return

        GLES20.glUseProgram(programId)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointSizeHandle, 5.0f)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
