package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import com.hereliesaz.graffitixr.feature.ar.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArRenderer(private val context: Context) {

    private var programId: Int = 0
    private var positionAttribute: Int = 0
    private var modelViewProjectionUniform: Int = 0
    private var colorUniform: Int = 0

    private var vboId: Int = 0
    private var numPoints: Int = 0

    // Stride for filtered rendering (Show 1 in every 20 points)
    private val POINT_STRIDE = 20

    fun createOnGlThread() {
        val vertexShader = ShaderUtil.loadGLShader(
            "shaders/vertex_shader.glsl",
            GLES20.GL_VERTEX_SHADER,
            context
        )
        val fragmentShader = ShaderUtil.loadGLShader(
            "shaders/fragment_shader.glsl",
            GLES20.GL_FRAGMENT_SHADER,
            context
        )

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        positionAttribute = GLES20.glGetAttribLocation(programId, "a_Position")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(programId, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(programId, "u_Color")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]
    }

    fun updatePointCloud(pointCloud: PointCloud) {
        val buffer = pointCloud.points
        val pointsRemaining = buffer.remaining() / 4 // 4 floats per point (x, y, z, confidence)

        // MODIFICATION: Filter points (1 in 20)
        // We create a filtered buffer.
        // Worst case size is full size, but we'll only use a fraction.
        // For efficiency in a real app, you'd calculate exact size,
        // but for your sanity, we'll just skip iterations.

        val filteredPointCount = pointsRemaining / POINT_STRIDE
        if (filteredPointCount == 0) {
            numPoints = 0
            return
        }

        // 4 floats (x,y,z,conf) * 4 bytes per float
        val filteredBuffer = ByteBuffer.allocateDirect(filteredPointCount * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Loop through and pick every Nth point
        for (i in 0 until pointsRemaining step POINT_STRIDE) {
            val offset = i * 4
            filteredBuffer.put(buffer.get(offset))     // x
            filteredBuffer.put(buffer.get(offset + 1)) // y
            filteredBuffer.put(buffer.get(offset + 2)) // z
            filteredBuffer.put(buffer.get(offset + 3)) // confidence
        }

        filteredBuffer.position(0)
        numPoints = filteredPointCount

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            numPoints * 16, // 16 bytes per point
            filteredBuffer,
            GLES20.GL_DYNAMIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (numPoints == 0) return

        GLES20.glUseProgram(programId)

        // MODIFICATION: Enable Blending for Transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Optional: Disable depth mask if you want them to look "ghostly" and not occlude each other strictly
        GLES20.glDepthMask(false)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 4, GLES20.GL_FLOAT, false, 16, 0)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // Restore state
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}