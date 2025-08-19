package com.hereliesaz.MuralOverlay.rendering

import android.content.res.AssetManager
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer() : Closeable {

    private val QUAD_COORDS = floatArrayOf(
        -1.0f, -1.0f,
        -1.0f, +1.0f,
        +1.0f, -1.0f,
        +1.0f, +1.0f,
    )

    private val QUAD_TEXCOORDS = floatArrayOf(
        0.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 1.0f,
        1.0f, 0.0f,
    )

    private val quadCoords: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD_COORDS).also { it.rewind() }
    private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD_TEXCOORDS).also { it.rewind() }

    private var backgroundTexture: Texture? = null
    private var backgroundShader: Shader? = null
    private var transformedTexCoords: FloatBuffer? = null

    fun createOnGlThread(assets: AssetManager) {
        backgroundTexture = Texture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        backgroundShader = Shader(assets, "shaders/background_show_camera.vert", "shaders/background_show_camera.frag", null)
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }

        if (frame.timestamp == 0L) {
            return
        }

        backgroundShader?.use()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, backgroundTexture!!.getTextureId())

        val positionAttribute = GLES30.glGetAttribLocation(backgroundShader!!.getProgramId(), "a_Position")
        val texCoordAttribute = GLES30.glGetAttribLocation(backgroundShader!!.getProgramId(), "a_TexCoord")

        GLES30.glVertexAttribPointer(positionAttribute, 2, GLES30.GL_FLOAT, false, 0, quadCoords)
        GLES30.glVertexAttribPointer(texCoordAttribute, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)

        GLES30.glEnableVertexAttribArray(positionAttribute)
        GLES30.glEnableVertexAttribArray(texCoordAttribute)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(positionAttribute)
        GLES30.glDisableVertexAttribArray(texCoordAttribute)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    override fun close() {
        backgroundTexture?.close()
        backgroundShader?.close()
    }
}
