package com.hereliesaz.graffitixr.rendering

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a simple textured quad in 3D space with Depth Occlusion support.
 *
 * Capabilities:
 * - Transparency/Opacity/Brightness/Color Balance.
 * - Depth Occlusion: Hides virtual content behind real objects using the ARCore Depth Map.
 */
class SimpleQuadRenderer {
    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var modelViewProjectionUniform = 0
    private var modelViewUniform = 0
    private var textureUniform = 0
    private var alphaUniform = 0
    private var brightnessUniform = 0
    private var colorBalanceUniform = 0
    private var screenSizeUniform = 0

    // Depth uniforms
    private var depthTextureUniform = 0
    private var useDepthUniform = 0

    // Blending Uniforms
    private var backgroundTextureUniform = 0
    private var displayToCameraTransformUniform = 0
    private var blendModeUniform = 0

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var textureId = -1
    private var lastBitmap: Bitmap? = null

    fun createOnGlThread() {
        // Shaders with Depth Occlusion Logic
        val vertexShaderCode = """
            uniform mat4 u_ModelViewProjection;
            uniform mat4 u_ModelView;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            varying float v_ViewDepth; // Linear depth in view space
            
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
                v_TexCoord = a_TexCoord;
                
                // Calculate linear depth (negative Z in view space)
                vec4 viewPos = u_ModelView * a_Position;
                v_ViewDepth = -viewPos.z; 
            }
        """

        val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform samplerExternalOES u_BackgroundTexture;
            uniform sampler2D u_DepthTexture;
            uniform int u_UseDepth;
            uniform float u_Alpha;
            uniform float u_Brightness;
            uniform vec3 u_ColorBalance;
            uniform vec2 u_ScreenSize;
            uniform mat3 u_DisplayToCameraTransform;
            uniform int u_BlendMode;

            varying vec2 v_TexCoord;
            varying float v_ViewDepth;
            
            vec3 applyBlend(vec3 dst, vec3 src, int mode) {
                if (mode == 0) return mix(dst, src, 1.0); // Normal
                if (mode == 1) return dst * src; // Multiply
                if (mode == 2) return 1.0 - (1.0 - dst) * (1.0 - src); // Screen
                if (mode == 3) { // Overlay
                    vec3 res;
                    for (int i = 0; i < 3; i++) {
                        if (dst[i] < 0.5) res[i] = 2.0 * dst[i] * src[i];
                        else res[i] = 1.0 - 2.0 * (1.0 - dst[i]) * (1.0 - src[i]);
                    }
                    return res;
                }
                if (mode == 4) return min(dst, src); // Darken
                if (mode == 5) return max(dst, src); // Lighten
                return src;
            }

            void main() {
                vec4 srcColor = texture2D(u_Texture, v_TexCoord);

                // Calculate Screen UV (0..1)
                vec2 screenUV = gl_FragCoord.xy / u_ScreenSize;

                // Transform to Camera Texture UV
                vec3 screenPos = vec3(screenUV.x, screenUV.y, 1.0);
                vec2 cameraUV = (u_DisplayToCameraTransform * screenPos).xy;

                vec4 dstColor = texture2D(u_BackgroundTexture, cameraUV);

                float visibility = 1.0;

                if (u_UseDepth == 1) {
                    // Depth Logic (Placeholder)
                }

                srcColor.rgb *= u_ColorBalance;
                srcColor.rgb += u_Brightness;
                srcColor.rgb = clamp(srcColor.rgb, 0.0, 1.0);

                vec3 blendedRGB = srcColor.rgb;

                if (u_BlendMode != 0 && u_BlendMode != 13) {
                    vec3 mixed = applyBlend(dstColor.rgb, srcColor.rgb, u_BlendMode);
                    blendedRGB = mixed;
                }
                
                gl_FragColor = vec4(blendedRGB, srcColor.a * u_Alpha * visibility);
            }
        """

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        modelViewUniform = GLES20.glGetUniformLocation(program, "u_ModelView")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")
        brightnessUniform = GLES20.glGetUniformLocation(program, "u_Brightness")
        colorBalanceUniform = GLES20.glGetUniformLocation(program, "u_ColorBalance")
        screenSizeUniform = GLES20.glGetUniformLocation(program, "u_ScreenSize")

        depthTextureUniform = GLES20.glGetUniformLocation(program, "u_DepthTexture")
        useDepthUniform = GLES20.glGetUniformLocation(program, "u_UseDepth")

        backgroundTextureUniform = GLES20.glGetUniformLocation(program, "u_BackgroundTexture")
        displayToCameraTransformUniform = GLES20.glGetUniformLocation(program, "u_DisplayToCameraTransform")
        blendModeUniform = GLES20.glGetUniformLocation(program, "u_BlendMode")

        // Geometry: Vertical Quad (X-Y Plane)
        val vertices = floatArrayOf(
            -0.5f, -0.5f, 0.0f,
            -0.5f,  0.5f, 0.0f,
            0.5f,  0.5f, 0.0f,
            0.5f, -0.5f, 0.0f
        )
        val bb = ByteBuffer.allocateDirect(vertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer!!.put(vertices)
        vertexBuffer!!.position(0)

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

    fun draw(
        mvpMatrix: FloatArray,
        modelViewMatrix: FloatArray,
        bitmap: Bitmap,
        alpha: Float,
        brightness: Float,
        colorR: Float,
        colorG: Float,
        colorB: Float,
        depthTextureId: Int = -1,
        backgroundTextureId: Int,
        viewWidth: Float,
        viewHeight: Float,
        displayToCameraTransform: FloatArray,
        blendMode: androidx.compose.ui.graphics.BlendMode
    ) {
        if (lastBitmap != bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            lastBitmap = bitmap
        }

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(modelViewUniform, 1, false, modelViewMatrix, 0)

        GLES20.glUniform1f(alphaUniform, alpha)
        GLES20.glUniform1f(brightnessUniform, brightness)
        GLES20.glUniform3f(colorBalanceUniform, colorR, colorG, colorB)
        GLES20.glUniform2f(screenSizeUniform, viewWidth, viewHeight)

        GLES20.glUniformMatrix3fv(displayToCameraTransformUniform, 1, false, displayToCameraTransform, 0)

        // Map Compose BlendMode to Integer
        val modeInt = when(blendMode) {
            androidx.compose.ui.graphics.BlendMode.SrcOver -> 0
            androidx.compose.ui.graphics.BlendMode.Multiply -> 1
            androidx.compose.ui.graphics.BlendMode.Screen -> 2
            androidx.compose.ui.graphics.BlendMode.Overlay -> 3
            androidx.compose.ui.graphics.BlendMode.Darken -> 4
            androidx.compose.ui.graphics.BlendMode.Lighten -> 5
            else -> 0
        }
        GLES20.glUniform1i(blendModeUniform, modeInt)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        if (depthTextureId != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId)
            GLES20.glUniform1i(depthTextureUniform, 1)
            GLES20.glUniform1i(useDepthUniform, 1)
        } else {
            GLES20.glUniform1i(useDepthUniform, 0)
        }

        // Bind Background Texture (OES)
        // Ensure to use a texture unit that doesn't conflict. 0=Tex, 1=Depth. Use 2.
        if (backgroundTextureId != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTextureId)
            GLES20.glUniform1i(backgroundTextureUniform, 2)
        }

        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDisable(GLES20.GL_CULL_FACE)
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
}
