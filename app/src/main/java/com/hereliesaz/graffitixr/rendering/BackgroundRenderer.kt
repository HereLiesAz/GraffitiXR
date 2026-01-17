package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoord: FloatBuffer
    private lateinit var quadTexCoordTransformed: FloatBuffer

    private var backgroundProgram: Int = 0
    private var textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    var textureId: Int = -1
        private set

    private var quadPositionParam: Int = 0
    private var quadTexCoordParam: Int = 0

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val numVertices = 4
        if (!::quadVertices.isInitialized) {
            val bbVertices = ByteBuffer.allocateDirect(numVertices * 3 * 4)
            bbVertices.order(ByteOrder.nativeOrder())
            quadVertices = bbVertices.asFloatBuffer()
            quadVertices.put(QUAD_COORDS)
            quadVertices.position(0)
        }

        if (!::quadTexCoord.isInitialized) {
            val bbTexCoords = ByteBuffer.allocateDirect(numVertices * 2 * 4)
            bbTexCoords.order(ByteOrder.nativeOrder())
            quadTexCoord = bbTexCoords.asFloatBuffer()
            quadTexCoord.put(QUAD_TEXCOORDS)
            quadTexCoord.position(0)
        }

        if (!::quadTexCoordTransformed.isInitialized) {
            val bbTexCoordsTransformed = ByteBuffer.allocateDirect(numVertices * 2 * 4)
            bbTexCoordsTransformed.order(ByteOrder.nativeOrder())
            quadTexCoordTransformed = bbTexCoordsTransformed.asFloatBuffer()
        }

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        backgroundProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(backgroundProgram, vertexShader)
        GLES20.glAttachShader(backgroundProgram, fragmentShader)
        GLES20.glLinkProgram(backgroundProgram)

        quadPositionParam = GLES20.glGetAttribLocation(backgroundProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(backgroundProgram, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadVertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoordTransformed
            )
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glUseProgram(backgroundProgram)

        GLES20.glVertexAttribPointer(quadPositionParam, 3, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordTransformed)

        GLES20.glEnableVertexAttribArray(quadPositionParam)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
        )

        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
               gl_Position = a_Position;
               v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}