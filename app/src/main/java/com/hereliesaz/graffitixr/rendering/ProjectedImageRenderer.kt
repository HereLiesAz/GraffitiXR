package com.hereliesaz.graffitixr.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import androidx.compose.ui.graphics.BlendMode
import com.google.ar.core.Anchor
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.utils.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a user's OverlayLayer in 3D space, attached to an AR Anchor.
 * Supports affine transforms (Scale/Rotate/Translate) relative to the anchor.
 */
class ProjectedImageRenderer {

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var textureHandle: Int = 0
    private var alphaHandle: Int = 0
    private var colorBalanceHandle: Int = 0
    private var brightnessHandle: Int = 0
    private var contrastHandle: Int = 0

    private var vboId = 0
    private var vaoId = 0
    private var textureId = 0

    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // A simple quad centered at 0,0
    private val vertices = floatArrayOf(
        // X, Y, Z, U, V
        -0.5f, 0.5f, 0.0f, 0.0f, 0.0f, // Top Left
        -0.5f, -0.5f, 0.0f, 0.0f, 1.0f, // Bottom Left
        0.5f, 0.5f, 0.0f, 1.0f, 0.0f, // Top Right
        0.5f, -0.5f, 0.0f, 1.0f, 1.0f  // Bottom Right
    )

    private var isTextureLoaded = false
    private var pendingBitmap: Bitmap? = null

    fun createOnGlThread(context: Context) {
        val vertexShader = ShaderUtil.loadGLShader(TAG, VERTEX_SHADER, GLES30.GL_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, FRAGMENT_SHADER, GLES30.GL_FRAGMENT_SHADER)

        program = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)
        }

        positionHandle = GLES30.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES30.glGetAttribLocation(program, "a_TexCoord")
        mvpMatrixHandle = GLES30.glGetUniformLocation(program, "u_MvpMatrix")
        textureHandle = GLES30.glGetUniformLocation(program, "u_Texture")
        alphaHandle = GLES30.glGetUniformLocation(program, "u_Alpha")
        colorBalanceHandle = GLES30.glGetUniformLocation(program, "u_ColorBalance")
        brightnessHandle = GLES30.glGetUniformLocation(program, "u_Brightness")
        contrastHandle = GLES30.glGetUniformLocation(program, "u_Contrast")

        // Setup VAO/VBO
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]

        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)

        // Position: Offset 0, Stride 5*4 = 20
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(positionHandle)

        // TexCoord: Offset 3*4 = 12
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glEnableVertexAttribArray(texCoordHandle)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        // Texture
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    }

    fun setBitmap(bitmap: Bitmap) {
        pendingBitmap = bitmap
    }

    private fun updateTexture() {
        pendingBitmap?.let { bmp ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            bmp.recycle()
            pendingBitmap = null
            isTextureLoaded = true
        }
    }

    fun draw(viewMtx: FloatArray, projMtx: FloatArray, anchor: Anchor, layer: OverlayLayer) {
        if (!isTextureLoaded && pendingBitmap == null) return
        updateTexture()

        GLES30.glUseProgram(program)

        // 1. Calculate Model Matrix based on Anchor + Layer Transforms
        val anchorMatrix = FloatArray(16)
        anchor.pose.toMatrix(anchorMatrix, 0)

        Matrix.setIdentityM(modelMatrix, 0)

        // Apply Layer Offset relative to anchor
        // Convert screen-space pixels offset to meters roughly (Arbitrary Scaling)
        val xOffsetMeters = layer.offset.x * 0.001f
        val yOffsetMeters = layer.offset.y * -0.001f // Y is flipped in GL
        Matrix.translateM(modelMatrix, 0, xOffsetMeters, yOffsetMeters, 0f)

        // Apply Rotation (Z is roll)
        Matrix.rotateM(modelMatrix, 0, layer.rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, layer.rotationY, 0f, 1f, 0f)
        Matrix.rotateM(modelMatrix, 0, layer.rotationZ, 0f, 0f, 1f)

        // Apply Scale
        Matrix.scaleM(modelMatrix, 0, layer.scale, layer.scale, 1f)

        // Combine: Anchor * LocalTransform
        val worldMatrix = FloatArray(16)
        Matrix.multiplyMM(worldMatrix, 0, anchorMatrix, 0, modelMatrix, 0)

        // MVP
        val viewWorld = FloatArray(16)
        Matrix.multiplyMM(viewWorld, 0, viewMtx, 0, worldMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMtx, 0, viewWorld, 0)

        // Uniforms
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES30.glUniform1f(alphaHandle, layer.opacity)
        GLES30.glUniform3f(colorBalanceHandle, layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB)
        GLES30.glUniform1f(brightnessHandle, layer.brightness)
        GLES30.glUniform1f(contrastHandle, layer.contrast)

        // Bind Texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(textureHandle, 0)

        // Blending
        setBlendMode(layer.blendMode)

        // Draw
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun setBlendMode(mode: BlendMode) {
        GLES30.glEnable(GLES30.GL_BLEND)
        when (mode) {
            BlendMode.SrcOver -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.Screen -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
            BlendMode.Multiply -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            BlendMode.Plus -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            else -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        // Disable Depth Write for transparent objects to prevent occlusion issues
        GLES30.glDepthMask(false)
    }

    companion object {
        private const val TAG = "ProjectedImage"
        private const val VERTEX_SHADER = """#version 300 es
            uniform mat4 u_MvpMatrix;
            layout(location = 0) in vec3 a_Position;
            layout(location = 1) in vec2 a_TexCoord;
            out vec2 v_TexCoord;
            void main() {
                v_TexCoord = a_TexCoord;
                gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Alpha;
            uniform vec3 u_ColorBalance;
            uniform float u_Brightness;
            uniform float u_Contrast;
            in vec2 v_TexCoord;
            out vec4 FragColor;
            
            void main() {
                vec4 texColor = texture(u_Texture, v_TexCoord);
                
                // Contrast
                vec3 color = texColor.rgb;
                color = (color - 0.5) * u_Contrast + 0.5;
                
                // Brightness
                color += u_Brightness;
                
                // Color Balance
                color *= u_ColorBalance;
                
                FragColor = vec4(color, texColor.a * u_Alpha);
            }
        """
    }
}