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

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * This class renders the AR background from camera feed. It creates and hosts an OpenGL texture
 * given an ARCore SharedCamera.
 */
class BackgroundRenderer {
    private var quadCoords: FloatBuffer? = null
    private lateinit var quadTexCoords: FloatBuffer
    private var backgroundRenderer: Shader? = null
    var textureId = -1
        private set

    /**
     * Allocates and initializes OpenGL resources needed by the background renderer. Must be called on
     * the OpenGL thread, typically in [GLSurfaceView.Renderer.onSurfaceCreated].
     */
    @Throws(IOException::class)
    fun createOnGlThread() {
        // Generate the background texture.
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        val numVertices = 4
        if (numVertices != QUAD_COORDS.size / COORDS_PER_VERTEX) {
            throw RuntimeException("Unexpected number of vertices in BackgroundRenderer.")
        }
        val bbTexCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        bbTexCoords.order(ByteOrder.nativeOrder())
        quadTexCoords = bbTexCoords.asFloatBuffer()
        quadTexCoords.put(QUAD_COORDS)
        quadTexCoords.position(0)

        val bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        bbCoords.order(ByteOrder.nativeOrder())
        quadCoords = bbCoords.asFloatBuffer()
        quadCoords!!.put(QUAD_COORDS)
        quadCoords!!.position(0)

        backgroundRenderer =
            Shader(
                "shaders/screenquad.vert",
                "shaders/screenquad.frag",
            )
                .setDepthTest(false)
                .setDepthWrite(false)
        backgroundRenderer!!.create()
    }

    /**
     * Draws the AR background image. The image will be drawn such that virtual content rendered with
     * the matrices provided by [com.google.ar.core.Camera.getViewMatrix] and
     * [com.google.ar.core.Camera.getProjectionMatrix] will be drawn correctly
     * aligned with the background video.
     */
    fun draw(frame: Frame) {
        // If display rotation changed (also includes view size change), we need to re-query the texture
        // coordinates.
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords!!,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }
        if (frame.timestamp == 0L) {
            // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
            // drawing possible leftover data from previous sessions if the texture is reused.
            return
        }
        backgroundRenderer!!.setTexture("u_Texture", textureId)
        backgroundRenderer!!.setVertexAttrib("a_Position", 2, quadCoords)
        backgroundRenderer!!.setVertexAttrib("a_TexCoord", 2, quadTexCoords)
        backgroundRenderer!!.draw()
    }

    companion object {
        private val TAG = BackgroundRenderer::class.java.simpleName

        // Components of the texture coordinates.
        private const val COORDS_PER_VERTEX = 2
        private const val FLOAT_SIZE = 4
        private val QUAD_COORDS =
            floatArrayOf(
                -1.0f, -1.0f,
                -1.0f, +1.0f,
                +1.0f, -1.0f,
                +1.0f, +1.0f
            )
    }
}