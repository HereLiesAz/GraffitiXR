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

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Renders the detected AR planes.  */
class PlaneRenderer {
    private var planeProgram = 0
    private val textures = IntArray(1)
    private var planeXZPositionAlphaAttribute = 0
    private var planeModelUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var textureUniform = 0
    private var gridControlUniform = 0
    private var planeUvMatrixUniform = 0
    private var vertexBuffer: FloatBuffer? = null
    private var indexBuffer: ShortBuffer? = null
    private var indexCount = 0
    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16) // Projection matrix.
    private val planeUvMatrix = FloatArray(9) // 3x3 matrix for transforming texture coords.

    /**
     * Allocates and initializes OpenGL resources needed by the plane renderer. Must be called on the
     * OpenGL thread, typically in [GLSurfaceView.Renderer.onSurfaceCreated].
     *
     * @param context Needed to access shader source and texture assets.
     * @param gridDistanceInMeters The grid tile size in meters.
     */
    @Throws(IOException::class)
    fun createOnGlThread(context: Context, gridDistanceInMeters: Float) {
        val vertexShader =
            Shader.loadShader(
                GLES30.GL_VERTEX_SHADER,
                "shaders/plane.vert"
            )
        val fragmentShader =
            Shader.loadShader(
                GLES30.GL_FRAGMENT_SHADER,
                "shaders/plane.frag"
            )
        planeProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(planeProgram, vertexShader)
        GLES30.glAttachShader(planeProgram, fragmentShader)
        GLES30.glLinkProgram(planeProgram)
        GLES30.glUseProgram(planeProgram)
        Shader.checkGLError("Program creation")
        textureUniform = GLES30.glGetUniformLocation(planeProgram, "u_Texture")
        planeXZPositionAlphaAttribute = GLES30.glGetAttribLocation(planeProgram, "a_Position")
        planeModelUniform = GLES30.glGetUniformLocation(planeProgram, "u_Model")
        planeModelViewProjectionUniform =
            GLES30.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
        gridControlUniform = GLES30.glGetUniformLocation(planeProgram, "u_GridControl")
        planeUvMatrixUniform = GLES30.glGetUniformLocation(planeProgram, "u_PlaneUvMatrix")

        // Read the texture.
        val textureBitmap =
            BitmapFactory.decodeStream(context.assets.open("models/trigrid.png"))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glGenTextures(textures.size, textures, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, textureBitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        Shader.checkGLError("Texture loading")
        val gridControl = floatArrayOf(gridDistanceInMeters, gridDistanceInMeters)
        GLES30.glUniform2fv(gridControlUniform, 1, gridControl, 0)
    }

    /** Updates the plane model matrices and extents.  */
    private fun updatePlane(plane: Plane) {
        val planePolygon = plane.polygon
        if (planePolygon.capacity() == 0) {
            return
        }

        val buffer = planePolygon.asReadOnlyBuffer()
        val capacity = buffer.capacity()
        if (vertexBuffer == null || vertexBuffer!!.capacity() < capacity) {
            val bb = ByteBuffer.allocateDirect(capacity)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }
        vertexBuffer!!.rewind()
        vertexBuffer!!.put(buffer)
        vertexBuffer!!.rewind()

        // Each vertex has 2 components for the xz position and 2 components for the alpha.
        val numVertices = vertexBuffer!!.remaining() / 2
        if (indexBuffer == null || indexBuffer!!.capacity() < numVertices * 6) {
            val bb = ByteBuffer.allocateDirect(numVertices * 6 * 2)
            bb.order(ByteOrder.nativeOrder())
            indexBuffer = bb.asShortBuffer()
        }
        indexBuffer!!.rewind()
        var i = 0
        while (i < numVertices / 2 - 1) {
            // Each quad is made of two triangles.
            indexBuffer!!.put((2 * i).toShort())
            indexBuffer!!.put((2 * i + 1).toShort())
            indexBuffer!!.put((2 * i + 2).toShort())
            indexBuffer!!.put((2 * i + 2).toShort())
            indexBuffer!!.put((2 * i + 1).toShort())
            indexBuffer!!.put((2 * i + 3).toShort())
            i++
        }
        indexBuffer!!.rewind()
        indexCount = indexBuffer!!.remaining()
    }

    /**
     * Draws the collection of tracked planes, with texturing applied to indicate the surface.
     *
     * @param allPlanes The collection of planes to draw.
     * @param cameraPose The pose of the camera, used to reduce shimmering.
     * @param cameraProjection The projection matrix for the camera.
     */
    fun drawPlanes(
        allPlanes: Collection<Plane>,
        cameraPose: Pose,
        cameraProjection: FloatArray?
    ) {
        // Planes must be drawn with GL_BLEND enabled, and depth testing disabled
        // for transparency to work correctly.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(planeProgram)

        // Set the texture and shader properties for the plane.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glUniform1i(textureUniform, 0)

        // Draw all planes in the scene.
        for (plane in allPlanes) {
            val isTracking = plane.trackingState == TrackingState.TRACKING
            val isSubsumed = plane.subsumedBy != null
            if (!isTracking || isSubsumed) {
                continue
            }
            updatePlane(plane)
            plane.centerPose.toMatrix(modelMatrix, 0)

            // Get the current combined camera perspective and view matrices.
            val cameraView = FloatArray(16)
            cameraPose.inverse().toMatrix(cameraView, 0)
            Matrix.multiplyMM(modelViewMatrix, 0, cameraView, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, cameraProjection, 0, modelViewMatrix, 0)

            // Set the Model, ModelView, and ModelViewProjection matrices in the shader.
            GLES30.glUniformMatrix4fv(planeModelUniform, 1, false, modelMatrix, 0)
            GLES30.glUniformMatrix4fv(
                planeModelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0
            )

            updatePlaneUvMatrix(plane)
            GLES30.glUniformMatrix3fv(planeUvMatrixUniform, 1, false, planeUvMatrix, 0)
            GLES30.glEnableVertexAttribArray(planeXZPositionAlphaAttribute)
            GLES30.glVertexAttribPointer(
                planeXZPositionAlphaAttribute, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer
            )
            GLES30.glDrawElements(
                GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer
            )
        }

        // Clean up the state.
        GLES30.glDisableVertexAttribArray(planeXZPositionAlphaAttribute)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        Shader.checkGLError("After drawing planes")
    }

    private fun updatePlaneUvMatrix(plane: Plane) {
        val planeNormal = FloatArray(3)
        plane.centerPose.getTransformedAxis(1, 1.0f, planeNormal, 0)
        val angle = Math.atan2(-planeNormal[2].toDouble(), planeNormal[0].toDouble()).toFloat()
        val cos = Math.cos(angle.toDouble()).toFloat()
        val sin = Math.sin(angle.toDouble()).toFloat()
        val extentX = plane.extentX
        val extentZ = plane.extentZ
        planeUvMatrix[0] = cos / extentX
        planeUvMatrix[1] = -sin / extentX
        planeUvMatrix[2] = 0f
        planeUvMatrix[3] = sin / extentZ
        planeUvMatrix[4] = cos / extentZ
        planeUvMatrix[5] = 0f
        planeUvMatrix[6] = 0f
        planeUvMatrix[7] = 0f
        planeUvMatrix[8] = 1f
    }

    companion object {
        private val TAG = PlaneRenderer::class.java.simpleName
    }
}