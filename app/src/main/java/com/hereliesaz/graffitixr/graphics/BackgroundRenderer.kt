package com.hereliesaz.graffitixr.graphics

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * This class renders the AR background from camera feed. It creates and hosts the texture
 * given to ARCore to be filled with the camera image.
 */
class BackgroundRenderer {
    private var quadCoords: FloatBuffer? = null
    private var quadTexCoords: FloatBuffer? = null
    private var quadTexCoordsCanonical: FloatBuffer? = null

    private var quadProgram = 0
    private var quadPositionAttrib = 0
    private var quadTexCoordAttrib = 0
    var textureId = -1
        private set

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        val numVertices = 4
        val bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * BYTES_PER_FLOAT)
        bbCoords.order(ByteOrder.nativeOrder())
        quadCoords = bbCoords.asFloatBuffer()
        quadCoords!!.put(QUAD_COORDS)
        quadCoords!!.position(0)

        val bbTexCoords = ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * BYTES_PER_FLOAT)
        bbTexCoords.order(ByteOrder.nativeOrder())
        quadTexCoords = bbTexCoords.asFloatBuffer()

        val bbTexCoordsCanonical =
            ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * BYTES_PER_FLOAT)
        bbTexCoordsCanonical.order(ByteOrder.nativeOrder())
        quadTexCoordsCanonical = bbTexCoordsCanonical.asFloatBuffer()
        quadTexCoordsCanonical!!.put(QUAD_TEX_COORDS)
        quadTexCoordsCanonical!!.position(0)

        val vertexShader = ShaderUtil.loadGLShader(TAG, VERTEX_SHADER, GLES20.GL_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, FRAGMENT_SHADER, GLES20.GL_FRAGMENT_SHADER)

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vertexShader)
        GLES20.glAttachShader(quadProgram, fragmentShader)
        GLES20.glLinkProgram(quadProgram)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(quadProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(quadProgram)
            GLES20.glDeleteProgram(quadProgram)
            throw RuntimeException("Error linking program: $log")
        }

        GLES20.glUseProgram(quadProgram)

        quadPositionAttrib = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordAttrib = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        GLES20.glUniform1i(GLES20.glGetUniformLocation(quadProgram, "sTexture"), 0)
    }

    fun draw(frame: Frame) {
        if (frame.timestamp != 0L) {
            // Suppress rendering if the camera did not produce the first frame yet.
            // This is to avoid drawing possible leftover data from previous sessions if the texture is reused.
            frame.transformCoordinates2d(
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoordsCanonical!!,
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadTexCoords!!
            )
        }

        GLES20.glDepthMask(false)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(quadProgram)
        GLES20.glVertexAttribPointer(quadPositionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(quadTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(quadPositionAttrib)
        GLES20.glEnableVertexAttribArray(quadTexCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(quadPositionAttrib)
        GLES20.glDisableVertexAttribArray(quadTexCoordAttrib)
        GLES20.glDepthMask(true)
    }

    companion object {
        private val TAG = BackgroundRenderer::class.java.simpleName
        private const val COORDS_PER_VERTEX = 2
        private const val TEXCOORDS_PER_VERTEX = 2
        private const val BYTES_PER_FLOAT = 4
        private val QUAD_COORDS = floatArrayOf(-1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f, 1.0f)
        private val QUAD_TEX_COORDS = floatArrayOf(
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
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """
    }
}