package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES30
import java.io.Closeable

class Texture(
    val target: Int = GLES30.GL_TEXTURE_2D,
    val wrapMode: Int = GLES30.GL_CLAMP_TO_EDGE,
    val minFilter: Int = GLES30.GL_LINEAR_MIPMAP_LINEAR,
    val magFilter: Int = GLES30.GL_LINEAR
) : Closeable {

    private val textureId: Int

    init {
        val textureIdArray = IntArray(1)
        GLES30.glGenTextures(1, textureIdArray, 0)
        textureId = textureIdArray[0]
        GLES30.glBindTexture(target, textureId)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, wrapMode)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, wrapMode)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, magFilter)
    }

    fun getTextureId(): Int {
        return textureId
    }

    override fun close() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
}
