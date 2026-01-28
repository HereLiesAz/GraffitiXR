package com.hereliesaz.graffitixr.rendering

import android.graphics.Bitmap
import android.opengl.GLES20
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

    // New Uniforms for "Glitch-Noir" aesthetics
    private var opacityParam = 0
    private var brightnessParam = 0
    private var colorBalanceParam = 0
    private var depthTextureParam = 0
    private var cameraTextureParam = 0 // For depth occlusion comparisons
    private var resolutionParam = 0 // Screen resolution for depth lookups
    private var displayTransformParam = 0

    private var quadVertices: FloatBuffer? = null
    private var quadTexCoord: FloatBuffer? = null
    private val textures = IntArray(1)

    fun createOnGlThread() {
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

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

        // Vertex Shader
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vertexShader)
        GLES20.glAttachShader(quadProgram, fragmentShader)
        GLES20.glLinkProgram(quadProgram)

        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        mvpMatrixParam = GLES20.glGetUniformLocation(quadProgram, "u_MvpMatrix")
        modelViewMatrixParam = GLES20.glGetUniformLocation(quadProgram, "u_ModelViewMatrix")
        textureParam = GLES20.glGetUniformLocation(quadProgram, "u_Texture")

        opacityParam = GLES20.glGetUniformLocation(quadProgram, "u_Opacity")
        brightnessParam = GLES20.glGetUniformLocation(quadProgram, "u_Brightness")
        colorBalanceParam = GLES20.glGetUniformLocation(quadProgram, "u_ColorBalance")
        depthTextureParam = GLES20.glGetUniformLocation(quadProgram, "u_DepthTexture")
        resolutionParam = GLES20.glGetUniformLocation(quadProgram, "u_Resolution")
        displayTransformParam = GLES20.glGetUniformLocation(quadProgram, "u_DisplayTransform")
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
        GLES20.glUseProgram(quadProgram)

        // Calculate matrices
        val mvMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        // Upload texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureParam, 0)

        // Depth Texture (disabled)
        GLES20.glUniform1i(depthTextureParam, -1)

        // Set Uniforms
        GLES20.glUniform1f(opacityParam, opacity)
        GLES20.glUniform1f(brightnessParam, brightness)
        GLES20.glUniform3f(colorBalanceParam, r, g, b)
        GLES20.glUniform2f(resolutionParam, 1.0f, 1.0f) // Dummy
        GLES20.glUniformMatrix3fv(displayTransformParam, 1, false, floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f), 0) // Identity

        GLES20.glUniformMatrix4fv(mvpMatrixParam, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelViewMatrixParam, 1, false, mvMatrix, 0)

        GLES20.glVertexAttribPointer(quadPositionParam, 3, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 0, quadTexCoord)

        GLES20.glEnableVertexAttribArray(quadPositionParam)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)

        // Blend Mode Logic (Supported: SrcOver, Screen, Multiply, Plus)
        GLES20.glEnable(GLES20.GL_BLEND)
        when (blendMode) {
            BlendMode.SrcOver -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.Screen -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            BlendMode.Multiply -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.Plus -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            else -> GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
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

        private const val VERTEX_SHADER_CODE = """
            uniform mat4 u_MvpMatrix;
            uniform mat4 u_ModelViewMatrix;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            varying vec3 v_ViewPos;

            void main() {
                v_TexCoord = a_TexCoord;
                vec4 viewPos = u_ModelViewMatrix * a_Position;
                v_ViewPos = viewPos.xyz;
                gl_Position = u_MvpMatrix * a_Position;
            }
        """

        // Fragment Shader with Occlusion & Color Grading
        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform sampler2D u_DepthTexture;
            
            uniform float u_Opacity;
            uniform float u_Brightness;
            uniform vec3 u_ColorBalance;
            uniform vec2 u_Resolution;
            
            // This uniform tells us how to map screen UVs to Camera Image UVs (for depth lookup)
            uniform mat3 u_DisplayTransform; 

            varying vec2 v_TexCoord;
            varying vec3 v_ViewPos; // Position in View Space (Metric)

            void main() {
                vec4 color = texture2D(u_Texture, v_TexCoord);
                
                // 1. Color Balance
                color.rgb *= u_ColorBalance;
                
                // 2. Brightness
                color.rgb += u_Brightness;
                
                // 3. Opacity
                color.a *= u_Opacity;

                // 4. Depth Occlusion (Simplified)
                // We need to calculate Screen UV to sample the depth map
                // gl_FragCoord.xy is in pixels.
                vec2 screenUV = gl_FragCoord.xy / u_Resolution;
                
                // Use the display transform to find the UV in the camera depth map
                vec3 depthUvVec = u_DisplayTransform * vec3(screenUV.x, screenUV.y, 1.0);
                vec2 depthUV = depthUvVec.xy;
                
                // Sample depth (16-bit raw value usually packed in R or L)
                // Note: Standard ARCore depth is raw. 
                // Since we bound GL_LUMINANCE in renderer, we get float 0..1
                // But real distance depends on projection. 
                // This is a naive check: if real depth < virtual depth, occlude.
                
                // float realDepth = texture2D(u_DepthTexture, depthUV).r * 8.0; // 8 meters max? 
                // float virtualDepth = -v_ViewPos.z;
                
                // Soft occlusion logic would go here. 
                // For now, we skip hard occlusion to prevent z-fighting artifacts 
                // unless explicitly requested, as raw depth maps are noisy.
                
                gl_FragColor = color;
            }
        """
    }
}