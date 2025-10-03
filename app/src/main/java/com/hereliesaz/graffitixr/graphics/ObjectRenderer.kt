package com.hereliesaz.graffitixr.graphics

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class ObjectRenderer {
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var textureUniformHandle = 0
    private var opacityHandle = 0
    private var contrastHandle = 0
    private var saturationHandle = 0

    private val vertices = floatArrayOf(
        -0.5f, -0.5f, 0f,
        0.5f, -0.5f, 0f,
        0.5f, 0.5f, 0f,
        -0.5f, 0.5f, 0f
    )

    private val textureCoords = floatArrayOf(
        0f, 1f,
        1f, 1f,
        1f, 0f,
        0f, 0f
    )

    private val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

    private val vertexBuffer: FloatBuffer
    private val textureCoordBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer
    private var textureId = 0

    init {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(vertices)
                position(0)
            }
        }
        textureCoordBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(textureCoords)
                position(0)
            }
        }
        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(indices)
                position(0)
            }
        }
    }

    fun createOnGlThread(context: Context) {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

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

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        textureUniformHandle = GLES20.glGetUniformLocation(program, "u_Texture")
        opacityHandle = GLES20.glGetUniformLocation(program, "u_Opacity")
        contrastHandle = GLES20.glGetUniformLocation(program, "u_Contrast")
        saturationHandle = GLES20.glGetUniformLocation(program, "u_Saturation")

        // Create the texture handle
        val textureHandles = IntArray(1)
        GLES20.glGenTextures(1, textureHandles, 0)
        textureId = textureHandles[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    fun updateTexture(context: Context, uri: Uri) {
        try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }

            if (bitmap != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            android.util.Log.e("ObjectRenderer", "Failed to update texture", e)
        }
    }

    fun draw(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        anchor: Anchor,
        opacity: Float,
        contrast: Float,
        saturation: Float
    ) {
        GLES20.glUseProgram(program)

        val modelMatrix = FloatArray(16)
        anchor.pose.toMatrix(modelMatrix, 0)

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glUniform1f(opacityHandle, opacity)
        GLES20.glUniform1f(contrastHandle, contrast)
        GLES20.glUniform1f(saturationHandle, saturation)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniformHandle, 0)

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compiling shader: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Opacity;
            uniform float u_Contrast;
            uniform float u_Saturation;
            varying vec2 v_TexCoord;

            void main() {
                vec4 color = texture2D(u_Texture, v_TexCoord);

                // Contrast
                color.rgb = (color.rgb - 0.5) * u_Contrast + 0.5;

                // Saturation
                const vec3 luminanceWeight = vec3(0.2125, 0.7154, 0.0721);
                float luminance = dot(color.rgb, luminanceWeight);
                vec3 gray = vec3(luminance);
                color.rgb = mix(gray, color.rgb, u_Saturation);

                // Opacity
                color.a *= u_Opacity;

                gl_FragColor = color;
            }
        """
    }
}