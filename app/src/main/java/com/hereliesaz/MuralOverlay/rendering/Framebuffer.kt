package com.hereliesaz.MuralOverlay.rendering

import android.opengl.GLES30
import java.io.Closeable

class Framebuffer(val width: Int, val height: Int, val texture: Texture?) : Closeable {
    private val framebufferId: Int

    init {
        val framebufferIdArray = IntArray(1)
        GLES30.glGenFramebuffers(1, framebufferIdArray, 0)
        framebufferId = framebufferIdArray[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        if (texture != null) {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                texture.target,
                texture.getTextureId(),
                0
            )
        }
    }

    fun getFramebufferId(): Int {
        return framebufferId
    }

    override fun close() {
        if (framebufferId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
        }
    }
}
