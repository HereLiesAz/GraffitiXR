package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap

class PointCloudRenderer {
    private val TAG = "PointCloudRenderer"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var pointSizeHandle: Int = 0

    private val maxPoints = 50000
    private var accumulatedPointCount = 0
    private var vboId = 0

    // Local buffer to store accumulated points (x, y, z, confidence)
    private val localBuffer = FloatArray(maxPoints * 4)
    private val pointIdMap = HashMap<Int, Int>()

    fun createOnGlThread(context: Context) {
        val vertexShaderCode = context.assets.open("shaders/point_cloud.vert").bufferedReader().use { it.readText() }
        val fragmentShaderCode = context.assets.open("shaders/point_cloud.frag").bufferedReader().use { it.readText() }

        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

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

    fun update(pointCloud: PointCloud) {
        val points = pointCloud.points ?: return
        val ids = pointCloud.ids ?: return

        val numPoints = points.remaining() / 4
        var hasUpdates = false

        for (i in 0 until numPoints) {
            // Filter points to reduce load if necessary, but here we process all or stride
            // Assuming simple accumulation logic

            val id = ids.get(i)
            val x = points.get(i * 4)
            val y = points.get(i * 4 + 1)
            val z = points.get(i * 4 + 2)
            val conf = points.get(i * 4 + 3)

            if (pointIdMap.containsKey(id)) {
                // Update existing point
                val index = pointIdMap[id]!!
                val offset = index * 4
                val oldConf = localBuffer[offset + 3]
                if (conf > oldConf) {
                    localBuffer[offset] = x
                    localBuffer[offset+1] = y
                    localBuffer[offset+2] = z
                    localBuffer[offset+3] = conf
                    hasUpdates = true
                }
            } else if (accumulatedPointCount < maxPoints) {
                // Add new point
                val index = accumulatedPointCount
                pointIdMap[id] = index
                val offset = index * 4
                localBuffer[offset] = x
                localBuffer[offset+1] = y
                localBuffer[offset+2] = z
                localBuffer[offset+3] = conf
                accumulatedPointCount++
                hasUpdates = true
            }
        }

        if (hasUpdates) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)

            val byteBuffer = ByteBuffer.allocateDirect(accumulatedPointCount * 4 * 4).order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()
            floatBuffer.put(localBuffer, 0, accumulatedPointCount * 4)
            floatBuffer.position(0)

            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, accumulatedPointCount * 4 * 4, byteBuffer)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (accumulatedPointCount == 0) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // Enable blending for transparency effects if desired
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointSizeHandle, 15.0f) // Adjusted point size

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, accumulatedPointCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun clear() {
        accumulatedPointCount = 0
        pointIdMap.clear()
    }
}
