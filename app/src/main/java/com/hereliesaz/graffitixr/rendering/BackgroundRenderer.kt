package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Coordinates2d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer {

    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f,
        -1.0f, +1.0f,
        +1.0f, -1.0f,
        +1.0f, +1.0f
    ).toBuffer()

    private val quadTexCoords = floatArrayOf(
        0.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 1.0f,
        1.0f, 0.0f
    ).toBuffer()

    private val quadTexCoordsTransformed: FloatBuffer
    private var uvCoordsInitialized = false

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    var textureId = -1
        private set

    init {
        val bbTexCoordsTransformed = ByteBuffer.allocateDirect(quadTexCoords.capacity() * 4)
        bbTexCoordsTransformed.order(ByteOrder.nativeOrder())
        quadTexCoordsTransformed = bbTexCoordsTransformed.asFloatBuffer()
    }

    fun createOnGlThread() {
        Log.d(TAG, "createOnGlThread: Initializing background renderer texture")
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { shader ->
            GLES20.glShaderSource(shader, VERTEX_SHADER)
            GLES20.glCompileShader(shader)
        }
        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { shader ->
            GLES20.glShaderSource(shader, FRAGMENT_SHADER)
            GLES20.glCompileShader(shader)
        }

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            GLES20.glUseProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged() || !uvCoordsInitialized) {
            Log.d(TAG, "draw: Updating geometry coordinates. Initialized: $uvCoordsInitialized")
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadVertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoordsTransformed
            )
            uvCoordsInitialized = true
        }

        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordsTransformed)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDepthMask(true)
    }

    companion object {
        private const val TAG = "BackgroundRenderer"
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
            uniform samplerExternalOES s_Texture;
            void main() {
               gl_FragColor = texture2D(s_Texture, v_TexCoord);
            }
        """

        private fun FloatArray.toBuffer(): FloatBuffer {
            val bb = ByteBuffer.allocateDirect(size * 4)
            bb.order(ByteOrder.nativeOrder())
            val buffer = bb.asFloatBuffer()
            buffer.put(this)
            buffer.position(0)
            return buffer
        }
    }
}
