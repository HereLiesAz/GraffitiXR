package com.hereliesaz.graffitixr.rendering

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a simple, un-warped textured quadrilateral in 3D space.
 *
 * This class is used for the initial placement phase in AR mode, drawing the user's
 * selected image as a flat plane at a given pose.
 */
class SimpleQuadRenderer {
    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var modelViewProjectionUniform = 0
    private var textureUniform = 0
    private var alphaUniform = 0
    private var colorBalanceUniform = 0

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var textureId = -1

    private var lastBitmap: Bitmap? = null // To avoid reloading the same texture

    fun createOnGlThread() {
        val vertexShader =
            ShaderUtil.loadGLShader(TAG, VERTEX_SHADER, GLES20.GL_VERTEX_SHADER)
        val fragmentShader =
            ShaderUtil.loadGLShader(TAG, FRAGMENT_SHADER, GLES20.GL_FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Error linking program: $log")
        }

        GLES20.glUseProgram(program)

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")
        colorBalanceUniform = GLES20.glGetUniformLocation(program, "u_ColorBalance")

        val bb = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer!!.put(QUAD_COORDS)
        vertexBuffer!!.position(0)

        val bbTex = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        bbTex.order(ByteOrder.nativeOrder())
        texCoordBuffer = bbTex.asFloatBuffer()
        texCoordBuffer!!.put(TEX_COORDS)
        texCoordBuffer!!.position(0)

        // Generate texture handle
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
    }

    fun draw(modelMatrix: FloatArray, viewMatrix: FloatArray, projectionMatrix: FloatArray, bitmap: Bitmap, alpha: Float, colorR: Float, colorG: Float, colorB: Float) {
        if (lastBitmap != bitmap) {
            loadTexture(bitmap)
            lastBitmap = bitmap
        }

        GLES20.glUseProgram(program)

        val modelViewMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        val modelViewProjectionMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
        GLES20.glUniform1f(alphaUniform, alpha)
        GLES20.glUniform3f(colorBalanceUniform, colorR, colorG, colorB)

        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
    }

    private fun loadTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    companion object {
        private val TAG = SimpleQuadRenderer::class.java.simpleName
        private val QUAD_COORDS = floatArrayOf(-0.5f, -0.5f, 0.0f, -0.5f, 0.5f, 0.0f, 0.5f, 0.5f, 0.0f, 0.5f, -0.5f, 0.0f)
        private val TEX_COORDS = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f)
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
            uniform vec3 u_ColorBalance;
            varying vec2 v_TexCoord;
            void main() {
                vec4 color = texture2D(u_Texture, v_TexCoord);
                color.rgb *= u_ColorBalance;
                gl_FragColor = vec4(color.rgb, color.a * u_Alpha);
            }
        """
    }
}
