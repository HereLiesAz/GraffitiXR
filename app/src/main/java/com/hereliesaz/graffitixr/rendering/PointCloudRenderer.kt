package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.HashSet

/**
 * Renders the 3D Point Cloud.
 * NOW WITH MEMORY: Accumulates points over time to build a persistent world model.
 */
class PointCloudRenderer {
    private val TAG = "PointCloudRenderer"

    private val vertexShaderCode =
        "uniform mat4 u_MvpMatrix;" +
                "uniform float u_PointSize;" +
                "attribute vec4 a_Position;" +
                "void main() {" +
                "   gl_Position = u_MvpMatrix * vec4(a_Position.xyz, 1.0);" +
                "   gl_PointSize = u_PointSize;" +
                "}"

    private val fragmentShaderCode =
        "precision mediump float;" +
                "uniform vec4 u_Color;" +
                "void main() {" +
                "    vec2 coord = gl_PointCoord - vec2(0.5);" +
                "    if (length(coord) > 0.5) discard;" +
                "    gl_FragColor = u_Color;" +
                "}"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var colorHandle: Int = 0
    private var pointSizeHandle: Int = 0

    // The Persistent Memory
    private val maxPoints = 50000 // Cap at 50k points to save memory
    private var accumulatedPointCount = 0
    private var vboId = 0

    // We use a local float array for accumulation before uploading to GPU
    // 4 floats per point (x, y, z, confidence)
    private val localBuffer: FloatArray = FloatArray(maxPoints * 4)
    private val pointIds = HashSet<Int>() // To track what we've already seen

    fun createOnGlThread() {
        val vertexShader = ShaderUtil.loadGLShader(TAG, vertexShaderCode, GLES20.GL_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, fragmentShaderCode, GLES20.GL_FRAGMENT_SHADER)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
        pointSizeHandle = GLES20.glGetUniformLocation(program, "u_PointSize")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

        // Initialize empty VBO
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, maxPoints * 4 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Updates the persistent map with new data from ARCore.
     */
    fun update(pointCloud: PointCloud) {
        val points = pointCloud.points
        val ids = pointCloud.ids

        if (points == null || ids == null) return

        val numPoints = points.remaining() / 4
        var newPointsAdded = 0

        // Iterate through new points
        for (i in 0 until numPoints) {
            val id = ids.get(i)
            // If we haven't seen this point ID before, and we have space, add it.
            if (!pointIds.contains(id) && accumulatedPointCount < maxPoints) {
                pointIds.add(id)

                // Read x,y,z,confidence
                val x = points.get(i * 4)
                val y = points.get(i * 4 + 1)
                val z = points.get(i * 4 + 2)
                val conf = points.get(i * 4 + 3)

                // Append to local buffer
                val offset = accumulatedPointCount * 4
                localBuffer[offset] = x
                localBuffer[offset + 1] = y
                localBuffer[offset + 2] = z
                localBuffer[offset + 3] = conf

                accumulatedPointCount++
                newPointsAdded++
            }
        }

        // If we added points, upload the *new* range to the GPU
        if (newPointsAdded > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            val byteBuffer = ByteBuffer.allocateDirect(newPointsAdded * 4 * 4)
            byteBuffer.order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()

            // Copy just the new chunk
            val startOffset = (accumulatedPointCount - newPointsAdded) * 4
            floatBuffer.put(localBuffer, startOffset, newPointsAdded * 4)
            floatBuffer.position(0)

            // Upload to VBO at the correct offset
            GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER,
                startOffset * 4,
                newPointsAdded * 4 * 4,
                byteBuffer
            )
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (accumulatedPointCount == 0) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // Render: Cyan/Teal for that "Hacker" aesthetic
        GLES20.glUniform4f(colorHandle, 0.0f, 1.0f, 0.8f, 1.0f)
        GLES20.glUniform1f(pointSizeHandle, 10.0f) // Nice visible dots

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, accumulatedPointCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun clear() {
        accumulatedPointCount = 0
        pointIds.clear()
    }
}