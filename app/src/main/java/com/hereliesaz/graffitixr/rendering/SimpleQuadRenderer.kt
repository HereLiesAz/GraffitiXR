package com.hereliesaz.graffitixr.rendering

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a simple textured quad in 3D space.
 *
 * This is the primary renderer for the user's artwork in AR mode.
 * It maps the user's selected image onto a quad centered at (0,0,0) in the model space.
 *
 * Capabilities:
 * - Transparency/Opacity adjustment.
 * - Brightness adjustment.
 * - RGB Color Balance.
 * - Updates the GPU texture when the [Bitmap] changes.
 */
class SimpleQuadRenderer {
    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var modelViewProjectionUniform = 0
    private var textureUniform = 0
    private var alphaUniform = 0
    private var brightnessUniform = 0
    private var colorBalanceUniform = 0

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var textureId = -1
    private var lastBitmap: Bitmap? = null

    /**
     * Initializes the OpenGL resources. Must be called on the GL thread.
     */
    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")
        brightnessUniform = GLES20.glGetUniformLocation(program, "u_Brightness")
        colorBalanceUniform = GLES20.glGetUniformLocation(program, "u_ColorBalance")

        // Geometry: Vertical Quad (X-Y Plane). Z is 0.
        // This ensures Scale(s, s, 1) scales both Width and Height uniformly.
        // Vertices go from -0.5 to +0.5 so that width=1.0 and height=1.0.
        val vertices = floatArrayOf(
            -0.5f, -0.5f, 0.0f, // Bottom Left
            -0.5f,  0.5f, 0.0f, // Top Left
            0.5f,  0.5f, 0.0f, // Top Right
            0.5f, -0.5f, 0.0f  // Bottom Right
        )
        val bb = ByteBuffer.allocateDirect(vertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer!!.put(vertices)
        vertexBuffer!!.position(0)

        // Texture Coords (Standard)
        val texCoords = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        )
        val bbTex = ByteBuffer.allocateDirect(texCoords.size * 4)
        bbTex.order(ByteOrder.nativeOrder())
        texCoordBuffer = bbTex.asFloatBuffer()
        texCoordBuffer!!.put(texCoords)
        texCoordBuffer!!.position(0)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    /**
     * Draws the textured quad.
     *
     * @param modelMatrix The Model matrix (position, rotation, scale).
     * @param viewMatrix The View matrix (camera).
     * @param projectionMatrix The Projection matrix (lens).
     * @param bitmap The image to render. If changed since last frame, it uploads to GPU.
     * @param alpha Global opacity multiplier (0..1).
     * @param brightness Brightness offset (-1..1).
     * @param colorR Red channel multiplier.
     * @param colorG Green channel multiplier.
     * @param colorB Blue channel multiplier.
     */
    fun draw(modelMatrix: FloatArray, viewMatrix: FloatArray, projectionMatrix: FloatArray, bitmap: Bitmap, alpha: Float, brightness: Float, colorR: Float, colorG: Float, colorB: Float) {
        if (lastBitmap != bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            lastBitmap = bitmap
        }

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Calculate MVP
        val mvpMatrix = FloatArray(16)
        val modelViewMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(alphaUniform, alpha)
        GLES20.glUniform1f(brightnessUniform, brightness)
        GLES20.glUniform3f(colorBalanceUniform, colorR, colorG, colorB)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Alpha;
            uniform float u_Brightness;
            uniform vec3 u_ColorBalance;
            varying vec2 v_TexCoord;
            void main() {
                vec4 color = texture2D(u_Texture, v_TexCoord);
                color.rgb *= u_ColorBalance;
                color.rgb += u_Brightness;
                gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a * u_Alpha);
            }
        """
    }
}
