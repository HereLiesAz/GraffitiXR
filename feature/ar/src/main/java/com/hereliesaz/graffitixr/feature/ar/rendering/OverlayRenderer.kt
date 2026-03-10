package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the editor layer composite as a textured quad locked to the AR anchor.
 *
 * Coordinate space: anchor-local XY plane, Z = 0 facing the camera.
 * MVP = P × V × A  where A is the PnP-driven anchor matrix from MobileGS.
 *
 * Quad corners (counter-clockwise from bottom-left):
 *   (-halfW, -halfH, 0), (halfW, -halfH, 0), (-halfW, halfH, 0), (halfW, halfH, 0)
 * Rendered as GL_TRIANGLE_STRIP with alpha blending.
 */
class OverlayRenderer(private val context: Context) {

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var textureHandle = 0

    private val textureIds = IntArray(1)
    private var hasTexture = false

    private var vboId = 0

    @Volatile private var halfW = 0.5f
    @Volatile private var halfH = 0.5f
    @Volatile private var quadDirty = false

    private val mvpMatrix = FloatArray(16)

    fun createOnGlThread() {
        val vs = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs)
            GLES30.glAttachShader(it, fs)
            GLES30.glLinkProgram(it)
        }

        positionHandle = GLES30.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES30.glGetAttribLocation(program, "a_TexCoord")
        mvpMatrixHandle = GLES30.glGetUniformLocation(program, "u_MvpMatrix")
        textureHandle = GLES30.glGetUniformLocation(program, "u_Texture")

        // Allocate texture
        GLES30.glGenTextures(1, textureIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        // Allocate VBO
        val buf = IntArray(1)
        GLES30.glGenBuffers(1, buf, 0)
        vboId = buf[0]
        buildQuad()
    }

    /** Upload a new overlay composite bitmap. Must be called from the GL thread. */
    fun updateTexture(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        hasTexture = true
    }

    /**
     * Set the physical half-extents of the overlay quad (meters).
     * Thread-safe; actual VBO rebuild happens at the start of the next [draw] call.
     */
    fun setExtent(halfW: Float, halfH: Float) {
        this.halfW = halfW
        this.halfH = halfH
        quadDirty = true
    }

    /**
     * Draw the overlay quad.  Must be called from the GL thread.
     * No-op until [updateTexture] has been called at least once.
     */
    fun draw(viewMatrix: FloatArray, projMatrix: FloatArray, anchorMatrix: FloatArray) {
        if (!hasTexture || program == 0 || vboId == 0) return

        if (quadDirty) {
            buildQuad()
            quadDirty = false
        }

        // MVP = P × V × A
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, anchorMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])
        GLES30.glUniform1i(textureHandle, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)

        val stride = 5 * 4  // 5 floats × 4 bytes
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    // Must be called from the GL thread.
    private fun buildQuad() {
        val w = halfW
        val h = halfH
        // Triangle-strip quad: BL, BR, TL, TR  (each vertex: x, y, z, u, v)
        val data = floatArrayOf(
            -w, -h, 0f,  0f, 1f,
             w, -h, 0f,  1f, 1f,
            -w,  h, 0f,  0f, 0f,
             w,  h, 0f,  1f, 0f,
        )
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data); it.position(0) }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private const val TAG = "OverlayRenderer"

        private const val VERTEX_SHADER = """#version 300 es
            uniform mat4 u_MvpMatrix;
            in vec3 a_Position;
            in vec2 a_TexCoord;
            out vec2 v_TexCoord;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            in vec2 v_TexCoord;
            out vec4 FragColor;
            void main() {
                FragColor = texture(u_Texture, v_TexCoord);
            }
        """
    }
}
