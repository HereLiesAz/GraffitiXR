/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.common.rendering

import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Renders a point cloud.  */
class PointCloudRenderer {
    private var vbo = 0
    private var vboSize = 0
    private var program = 0
    private var positionAttribute = 0
    private var modelViewProjectionUniform = 0
    private var colorUniform = 0
    private var pointSizeUniform = 0
    private var numPoints = 0
    private var lastTimestamp: Long = 0

    /**
     * Allocates and initializes OpenGL resources needed by the plane renderer. Must be called on the
     * OpenGL thread, typically in [GLSurfaceView.Renderer.onSurfaceCreated].
     */
    @Throws(IOException::class)
    fun createOnGlThread() {
        val vertexShader =
            Shader.loadShader(
                GLES30.GL_VERTEX_SHADER,
                "shaders/point_cloud.vert"
            )
        val fragmentShader =
            Shader.loadShader(
                GLES30.GL_FRAGMENT_SHADER,
                "shaders/point_cloud.frag"
            )
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glUseProgram(program)
        Shader.checkGLError("Program creation")
        positionAttribute = GLES30.glGetAttribLocation(program, "a_Position")
        colorUniform = GLES30.glGetUniformLocation(program, "u_Color")
        pointSizeUniform = GLES30.glGetUniformLocation(program, "u_PointSize")
        modelViewProjectionUniform = GLES30.glGetUniformLocation(program, "u_ModelViewProjection")
        Shader.checkGLError("Program parameters")
        val vboBuffers = IntArray(1)
        GLES30.glGenBuffers(1, vboBuffers, 0)
        vbo = vboBuffers[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        vboSize = INITIAL_VBO_SIZE
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vboSize, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        Shader.checkGLError("Buffer creation")
    }

    /**
     * Updates the OpenGL buffer contents to the provided point. Repeated calls with the same point
     * cloud will be ignored.
     */
    fun update(cloud: PointCloud) {
        if (cloud.timestamp == lastTimestamp) {
            // Redundant call.
            return
        }
        Shader.checkGLError("Before update")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        lastTimestamp = cloud.timestamp

        // If the VBO is not large enough to fit the new point cloud, resize it.
        numPoints = cloud.points.remaining() / 4
        if (numPoints * BYTES_PER_POINT > vboSize) {
            while (numPoints * BYTES_PER_POINT > vboSize) {
                vboSize *= 2
            }
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vboSize, null, GLES30.GL_DYNAMIC_DRAW)
        }
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER, 0, numPoints * BYTES_PER_POINT, cloud.points
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        Shader.checkGLError("After update")
    }

    /**
     * Renders the point cloud.
     *
     * @param cameraView The camera view matrix for this frame, typically from [     ][com.google.ar.core.Camera.getViewMatrix].
     * @param cameraPerspective The camera perspective matrix for this frame, typically from [     ][com.google.ar.core.Camera.getProjectionMatrix].
     */
    fun draw(cameraView: FloatArray?, cameraPerspective: FloatArray?) {
        val modelViewProjection = FloatArray(16)
        Matrix.multiplyMM(modelViewProjection, 0, cameraPerspective, 0, cameraView, 0)
        Shader.checkGLError("Before draw")
        GLES30.glUseProgram(program)
        GLES30.glEnableVertexAttribArray(positionAttribute)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glVertexAttribPointer(positionAttribute, 4, GLES30.GL_FLOAT, false, BYTES_PER_POINT, 0)
        GLES30.glUniform4f(colorUniform, 31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f)
        GLES30.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjection, 0)
        GLES30.glUniform1f(pointSizeUniform, 5.0f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, numPoints)
        GLES30.glDisableVertexAttribArray(positionAttribute)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        Shader.checkGLError("After draw")
    }

    companion object {
        private const val BYTES_PER_POINT = 4 * 4 // Four floats per point.
        private const val INITIAL_VBO_SIZE = 1000 * BYTES_PER_POINT
    }
}