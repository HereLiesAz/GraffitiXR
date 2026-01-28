package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap

/**
 * Renders the 3D Point Cloud.
 * NOW WITH CONFIDENCE VISUALIZATION:
 * - Cyan: Low confidence (Scanning)
 * - Pink: Medium confidence (Acquiring)
 * - Green: High confidence (Saved/Locked)
 */
class PointCloudRenderer {
    private val TAG = "PointCloudRenderer"

    // Updated Vertex Shader: Using Raw String to prevent concatenation errors
    private val vertexShaderCode = """
        uniform mat4 u_MvpMatrix;
        uniform float u_PointSize;
        attribute vec4 a_Position; // x, y, z, confidence
        varying float v_Confidence;
        
        void main() {
           gl_Position = u_MvpMatrix * vec4(a_Position.xyz, 1.0);
           gl_PointSize = u_PointSize;
           v_Confidence = a_Position.w;
        }
    """.trimIndent()

    // Updated Fragment Shader: Fixed the comment bug by using Raw String
    private val fragmentShaderCode = """
        precision mediump float;
        varying float v_Confidence;
        
        void main() {
            vec2 coord = gl_PointCoord - vec2(0.5);
            if (length(coord) > 0.5) discard;
            
            // Colors
            vec3 cyan = vec3(0.0, 1.0, 1.0);
            vec3 pink = vec3(1.0, 0.0, 0.8);
            vec3 green = vec3(0.0, 1.0, 0.0);
            
            vec3 finalColor;
            
            // Confidence is usually 0.0 to 1.0
            if (v_Confidence < 0.5) {
                // Transition Cyan -> Pink
                finalColor = mix(cyan, pink, v_Confidence * 2.0);
            } else {
                // Transition Pink -> Green (Saved state)
                finalColor = mix(pink, green, (v_Confidence - 0.5) * 2.0);
            }
            
            gl_FragColor = vec4(finalColor, 1.0);
        }
    """.trimIndent()

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var pointSizeHandle: Int = 0

    // The Persistent Memory
    private val maxPoints = 50000 
    private var accumulatedPointCount = 0
    private var vboId = 0

    // Local buffer: x, y, z, confidence
    private val localBuffer: FloatArray = FloatArray(maxPoints * 4)
    
    // Map ID -> Index in localBuffer. Allows us to update existing points.
    private val pointIdMap = HashMap<Int, Int>() 

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
        pointSizeHandle = GLES20.glGetUniformLocation(program, "u_PointSize")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

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
        var hasUpdates = false

        for (i in 0 until numPoints) {
            val id = ids.get(i)
            
            // Read x,y,z,confidence from ARCore
            val x = points.get(i * 4)
            val y = points.get(i * 4 + 1)
            val z = points.get(i * 4 + 2)
            val conf = points.get(i * 4 + 3)

            if (pointIdMap.containsKey(id)) {
                // UPDATE: If we have this point, but new confidence is higher, update it.
                val index = pointIdMap[id]!!
                val offset = index * 4
                val oldConf = localBuffer[offset + 3]

                if (conf > oldConf) {
                    localBuffer[offset] = x
                    localBuffer[offset + 1] = y
                    localBuffer[offset + 2] = z
                    localBuffer[offset + 3] = conf
                    hasUpdates = true
                }
            } else {
                // Add if we have space
                if (accumulatedPointCount < maxPoints) {
                    val index = accumulatedPointCount
                    pointIdMap[id] = index
                    
                    val offset = index * 4
                    localBuffer[offset] = x
                    localBuffer[offset + 1] = y
                    localBuffer[offset + 2] = z
                    localBuffer[offset + 3] = conf

                    accumulatedPointCount++
                    hasUpdates = true
                }
            }
        }

        // If we changed anything (new points OR updates), upload the active buffer range.
        if (hasUpdates) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            val byteBuffer = ByteBuffer.allocateDirect(accumulatedPointCount * 4 * 4)
            byteBuffer.order(ByteOrder.nativeOrder())
            val floatBuffer = byteBuffer.asFloatBuffer()

            floatBuffer.put(localBuffer, 0, accumulatedPointCount * 4)
            floatBuffer.position(0)

            GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER,
                0,
                accumulatedPointCount * 4 * 4,
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
        GLES20.glUniform1f(pointSizeHandle, 15.0f) // Larger points for better visibility

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        // Stride is 16 (4 floats * 4 bytes). Data is x,y,z,conf.
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, accumulatedPointCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun clear() {
        accumulatedPointCount = 0
        pointIdMap.clear()
    }
}
