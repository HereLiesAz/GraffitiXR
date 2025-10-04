/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may
 obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.common.rendering

import android.opengl.GLES30
import java.io.Closeable

/**
 * A framebuffer.
 *
 *
 * This class is a wrapper around an OpenGL framebuffer object.
 *
 * @see [OpenGL Framebuffer
 * Object](https://www.khronos.org/registry/OpenGL-Refpages/es3.0/html/glBindFramebuffer.xhtml)
 */
class Framebuffer(width: Int, height: Int, texture: Texture) : Closeable {
    private val framebufferId = IntArray(1)
    private val depthTexture: Texture

    /**
     * Creates a new framebuffer.
     *
     * @param width the width of the framebuffer in pixels
     * @param height the height of the framebuffer in pixels
     * @param texture the texture to attach to the framebuffer
     */
    init {
        GLES30.glGenFramebuffers(1, framebufferId, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, texture.target, texture.textureId[0], 0
        )
        depthTexture = Texture(width, height, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, depthTexture.textureId[0], 0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("Framebuffer construction failed: $status")
        }
    }

    /**
     * Uses the framebuffer.
     *
     *
     * This method binds the framebuffer.
     */
    fun use(body: () -> Unit) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId[0])
        body()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun close() {
        if (framebufferId[0] != 0) {
            GLES30.glDeleteFramebuffers(1, framebufferId, 0)
        }
        depthTexture.close()
    }
}