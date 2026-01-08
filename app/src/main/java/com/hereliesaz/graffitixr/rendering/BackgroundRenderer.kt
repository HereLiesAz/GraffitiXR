package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the video background from the device camera.
 *
 * This renderer uses the `GL_TEXTURE_EXTERNAL_OES` texture target, which is required
 * for Android camera preview streams. It draws a full-screen quad behind all other 3D content.
 *
 * It also handles the transformation of texture coordinates to account for screen rotation
 * and aspect ratio differences between the camera sensor and the display.
 */
class BackgroundRenderer {
    private lateinit var quadCoords: FloatBuffer
    private lateinit var quadTexCoords: FloatBuffer
    private var areTexCoordsInitialized = false
    private var firstFrameLogged = false

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureUniform = 0

    /**
     * The OpenGL texture ID bound to the external camera stream.
     */
    var textureId = -1
        private set

    /**
     * Initializes the OpenGL resources (textures, shaders, buffers).
     * Must be called on the GL thread (e.g., in `onSurfaceCreated`).
     */
    fun createOnGlThread() {
        Log.d(TAG, "createOnGlThread: Initializing BackgroundRenderer")

        // Reset state to ensure clean initialization
        textureId = -1
        program = 0

        // Generate the background texture.
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        Log.d(TAG, "createOnGlThread: Generated Texture ID $textureId (Expected: >0)")

        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        val numVertices = 4
        // Always re-initialize buffers to handle context loss/recreation correctly
        val bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        bbCoords.order(ByteOrder.nativeOrder())
        quadCoords = bbCoords.asFloatBuffer()
        quadCoords.put(QUAD_COORDS)
        quadCoords.position(0)

        val bbTexCoords = ByteBuffer.allocateDirect(numVertices * 2 * 4)
        bbTexCoords.order(ByteOrder.nativeOrder())
        quadTexCoords = bbTexCoords.asFloatBuffer()

        // Reset initialization flag
        areTexCoordsInitialized = false
        firstFrameLogged = false

        Log.d(TAG, "createOnGlThread: Compiling shaders")
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glUseProgram(program)
        checkGLError("createOnGlThread: After useProgram")

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            Log.e(TAG, "Program link failed: $log")
            throw RuntimeException("Program link failed: $log")
        }
        Log.d(TAG, "createOnGlThread: Program linked successfully. ID: $program")

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "sTexture")

        Log.d(TAG, "createOnGlThread: Handles - Pos: $positionHandle, Tex: $texCoordHandle, Uniform: $textureUniform")

        if (textureUniform == -1) {
            throw RuntimeException("Could not get uniform location for sTexture")
        }

        checkGLError("createOnGlThread")
    }

    /**
     * Draws the camera background.
     * @param frame The current AR frame, used to transform texture coordinates.
     */
    fun draw(frame: Frame) {
        // If display rotation changed (also includes view size change), we need to re-query the texture
        // coordinates for the screen background, as they are tailored to the screen aspect ratio.
        if (frame.hasDisplayGeometryChanged() || !areTexCoordsInitialized) {
            Log.d(TAG, "draw: updating geometry. hasDisplayGeometryChanged=${frame.hasDisplayGeometryChanged()}, areTexCoordsInitialized=$areTexCoordsInitialized")
            quadCoords.position(0)
            quadTexCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
            areTexCoordsInitialized = true
        }

        if (frame.timestamp == 0L) {
            Log.w(TAG, "draw: frame timestamp is 0, skipping")
            return
        }

        // Debug: Check for pre-existing errors
        checkGLError("Before draw")

        if (!firstFrameLogged) {
            Log.d(TAG, "draw: First Frame drawing. TextureID: $textureId, Program: $program")
            firstFrameLogged = true
        }

        if (program == 0 || textureId == -1) {
            Log.e(TAG, "draw: Invalid state. Program: $program, TextureID: $textureId")
            return
        }

        // Disable depth test to ensure background is always drawn "behind" everything
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(false)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        checkGLError("After useProgram")

        GLES20.glUniform1i(textureUniform, 0)

        quadCoords.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glEnableVertexAttribArray(positionHandle)

        quadTexCoords.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        checkGLError("After attribs")

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGLError("After drawArrays")

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Log.e(TAG, "Shader compilation failed: $log")
            throw RuntimeException("Shader compilation failed: $log")
        }

        return shader
    }

    private fun checkGLError(tag: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$tag: glError $error")
            // Throwing RuntimeException here crashes the app in production if any GL error occurs.
            // Logging is sufficient for debugging.
            // throw RuntimeException("$tag: glError $error")
        }
    }

    companion object {
        private const val TAG = "BackgroundRenderer"
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, -1.0f,
            +1.0f, +1.0f
        )

        private val VERTEX_SHADER = """
            attribute vec2 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
               gl_Position = vec4(a_Position, 0.0, 1.0);
               v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """.trimIndent()
    }
}
