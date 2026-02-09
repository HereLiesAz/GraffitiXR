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
    private var pointSizeUniform = 0

    private var numPoints = 0
    private var lastTimestamp: Long = 0

    // Missing properties
    private val pointIdMap = HashMap<Int, Int>()
    private val maxPoints = 1000 // Or appropriate default
    private val localBuffer = FloatArray(maxPoints * 4)
    private var accumulatedPointCount = 0

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
        pointSizeUniform = GLES20.glGetUniformLocation(programId, "u_PointSize")
    }

    fun update(pointCloud: PointCloud) {
        val points = pointCloud.points
        val ids = pointCloud.ids
        if (points == null || ids == null) return
        val pointsInFrame = points.remaining() / 4
        var hasUpdates = false
        for (i in 0 until pointsInFrame) {
            if (i % 20 != 0) continue

            val id = ids.get(i)
            val x = points.get(i * 4)
            val y = points.get(i * 4 + 1)
            val z = points.get(i * 4 + 2)
            val conf = points.get(i * 4 + 3)
            if (pointIdMap.containsKey(id)) {
                val index = pointIdMap[id]!!
                val offset = index * 4
                val oldConf = localBuffer[offset + 3]
                if (conf > oldConf) {
                    localBuffer[offset] = x; localBuffer[offset+1] = y; localBuffer[offset+2] = z; localBuffer[offset+3] = conf
                    hasUpdates = true
                }
            } else if (accumulatedPointCount < maxPoints) {
                val index = accumulatedPointCount
                pointIdMap[id] = index
                val offset = index * 4
                localBuffer[offset] = x; localBuffer[offset+1] = y; localBuffer[offset+2] = z; localBuffer[offset+3] = conf
                accumulatedPointCount++
                hasUpdates = true
            }
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
        GLES20.glUniform1f(pointSizeUniform, 2.5f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(positionAttribute, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, accumulatedPointCount)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}