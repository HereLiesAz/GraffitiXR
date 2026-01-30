package com.hereliesaz.graffitixr.rendering

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.compose.ui.graphics.BlendMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SimpleQuadRenderer {
    private var quadProgram = 0
    private var quadPositionParam = 0
    private var quadTexCoordParam = 0
    private var mvpMatrixParam = 0
    private var modelViewMatrixParam = 0
    private var textureParam = 0

    private var opacityParam = 0
    private var brightnessParam = 0
    private var colorBalanceParam = 0

    // Depth disabled for now, uniforms kept for future compatibility
    // private var depthTextureParam = 0
    // private var resolutionParam = 0
    // private var displayTransformParam = 0

    private var quadVertices: FloatBuffer? = null
    private var quadTexCoord: FloatBuffer? = null
    private val textures = IntArray(1)

    fun createOnGlThread() {
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val numVertices = 4
        if (quadVertices == null) {
            val bbVertices = ByteBuffer.allocateDirect(numVertices * 3 * 4)
            bbVertices.order(ByteOrder.nativeOrder())
            quadVertices = bbVertices.asFloatBuffer()
            quadVertices?.put(QUAD_COORDS)
            quadVertices?.position(0)
        }

        if (quadTexCoord == null) {
            val bbTexCoords = ByteBuffer.allocateDirect(numVertices * 2 * 4)
            bbTexCoords.order(ByteOrder.nativeOrder())
            quadTexCoord = bbTexCoords.asFloatBuffer()
            quadTexCoord?.put(QUAD_TEXCOORDS)
            quadTexCoord?.position(0)
        }

        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        quadProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(quadProgram, vertexShader)
        GLES30.glAttachShader(quadProgram, fragmentShader)
        GLES30.glLinkProgram(quadProgram)

        quadPositionParam = GLES30.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES30.glGetAttribLocation(quadProgram, "a_TexCoord")
        mvpMatrixParam = GLES30.glGetUniformLocation(quadProgram, "u_MvpMatrix")
        modelViewMatrixParam = GLES30.glGetUniformLocation(quadProgram, "u_ModelViewMatrix")
        textureParam = GLES30.glGetUniformLocation(quadProgram, "u_Texture")

        opacityParam = GLES30.glGetUniformLocation(quadProgram, "u_Opacity")
        brightnessParam = GLES30.glGetUniformLocation(quadProgram, "u_Brightness")
        colorBalanceParam = GLES30.glGetUniformLocation(quadProgram, "u_ColorBalance")
    }

    fun draw(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        modelMatrix: FloatArray,
        textureId: Int,
        opacity: Float,
        brightness: Float,
        r: Float, g: Float, b: Float,
        blendMode: BlendMode
    ) {
        GLES30.glUseProgram(quadProgram)

        val mvMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(textureParam, 0)

        GLES30.glUniform1f(opacityParam, opacity)
        GLES30.glUniform1f(brightnessParam, brightness)
        GLES30.glUniform3f(colorBalanceParam, r, g, b)

        GLES30.glUniformMatrix4fv(mvpMatrixParam, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(modelViewMatrixParam, 1, false, mvMatrix, 0)

        GLES30.glVertexAttribPointer(quadPositionParam, 3, GLES30.GL_FLOAT, false, 0, quadVertices)
        GLES30.glVertexAttribPointer(quadTexCoordParam, 2, GLES30.GL_FLOAT, false, 0, quadTexCoord)

        GLES30.glEnableVertexAttribArray(quadPositionParam)
        GLES30.glEnableVertexAttribArray(quadTexCoordParam)

        GLES30.glEnable(GLES30.GL_BLEND)
        when (blendMode) {
            BlendMode.SrcOver -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.Screen -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
            BlendMode.Multiply -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.Plus -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            else -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(quadPositionParam)
        GLES30.glDisableVertexAttribArray(quadTexCoordParam)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        return shader
    }

    companion object {
        private val QUAD_COORDS = floatArrayOf(
            -0.5f, 0.0f, -0.5f,
            -0.5f, 0.0f, 0.5f,
            0.5f, 0.0f, -0.5f,
            0.5f, 0.0f, 0.5f
        )

        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
        )

        private const val VERTEX_SHADER_CODE = """#version 300 es
            layout(location = 0) in vec4 a_Position;
            layout(location = 1) in vec2 a_TexCoord;
            
            uniform mat4 u_MvpMatrix;
            uniform mat4 u_ModelViewMatrix;
            
            out vec2 v_TexCoord;
            out vec3 v_ViewPos;

            void main() {
                v_TexCoord = a_TexCoord;
                vec4 viewPos = u_ModelViewMatrix * a_Position;
                v_ViewPos = viewPos.xyz;
                gl_Position = u_MvpMatrix * a_Position;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """#version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            
            uniform float u_Opacity;
            uniform float u_Brightness;
            uniform vec3 u_ColorBalance;

            in vec2 v_TexCoord;
            in vec3 v_ViewPos;
            out vec4 FragColor;

            void main() {
                vec4 color = texture(u_Texture, v_TexCoord);
                
                color.rgb *= u_ColorBalance;
                color.rgb += u_Brightness;
                color.a *= u_Opacity;
                
                FragColor = color;
            }
        """
    }
}