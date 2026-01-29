package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoord: FloatBuffer
    private lateinit var quadTexCoordTransformed: FloatBuffer
    private lateinit var quadCoords2D: FloatBuffer

    private var backgroundProgram: Int = 0
    private var textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    var textureId: Int = -1
        private set

    private var quadPositionParam: Int = 0
    private var quadTexCoordParam: Int = 0
    private var uTextureParam: Int = 0
    private var hasTransformedCoords = false

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val numVertices = 4
        if (!::quadVertices.isInitialized) {
            val bbVertices = ByteBuffer.allocateDirect(numVertices * 3 * 4)
            bbVertices.order(ByteOrder.nativeOrder())
            quadVertices = bbVertices.asFloatBuffer()
            quadVertices.put(QUAD_COORDS)
            quadVertices.position(0)
        }

        if (!::quadCoords2D.isInitialized) {
            val bbCoords2D = ByteBuffer.allocateDirect(numVertices * 2 * 4)
            bbCoords2D.order(ByteOrder.nativeOrder())
            quadCoords2D = bbCoords2D.asFloatBuffer()
            quadCoords2D.put(floatArrayOf(
                -1.0f, -1.0f,
                -1.0f, 1.0f,
                1.0f, -1.0f,
                1.0f, 1.0f
            ))
            quadCoords2D.position(0)
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

        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        backgroundProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(backgroundProgram, vertexShader)
        GLES30.glAttachShader(backgroundProgram, fragmentShader)
        GLES30.glLinkProgram(backgroundProgram)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(backgroundProgram, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e("BackgroundRenderer", "Error linking program: " + GLES30.glGetProgramInfoLog(backgroundProgram))
        }

        quadPositionParam = GLES30.glGetAttribLocation(backgroundProgram, "a_Position")
        quadTexCoordParam = GLES30.glGetAttribLocation(backgroundProgram, "a_TexCoord")
        uTextureParam = GLES30.glGetUniformLocation(backgroundProgram, "u_Texture")

        hasTransformedCoords = false
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged() || !hasTransformedCoords) {
            quadCoords2D.position(0)
            quadTexCoordTransformed.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords2D,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoordTransformed
            )
            hasTransformedCoords = true
        }

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glUseProgram(backgroundProgram)
        GLES30.glUniform1i(uTextureParam, 0)

        quadVertices.position(0)
        GLES30.glVertexAttribPointer(quadPositionParam, 3, GLES30.GL_FLOAT, false, 0, quadVertices)
        GLES30.glEnableVertexAttribArray(quadPositionParam)

        quadTexCoordTransformed.position(0)
        GLES30.glVertexAttribPointer(quadTexCoordParam, 2, GLES30.GL_FLOAT, false, 0, quadTexCoordTransformed)
        GLES30.glEnableVertexAttribArray(quadTexCoordParam)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(quadPositionParam)
        GLES30.glDisableVertexAttribArray(quadTexCoordParam)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e("BackgroundRenderer", "Error compiling shader: " + GLES30.glGetShaderInfoLog(shader))
            GLES30.glDeleteShader(shader)
            return 0
        }

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

        // Updated to GLES 3.0
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec4 a_Position;
            layout(location = 1) in vec2 a_TexCoord;
            out vec2 v_TexCoord;
            void main() {
               gl_Position = a_Position;
               v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            out vec4 FragColor;
            void main() {
                FragColor = texture(u_Texture, v_TexCoord);
            }
        """
    }
}