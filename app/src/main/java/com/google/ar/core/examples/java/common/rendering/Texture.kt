/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.io.Closeable

/**
 * A texture.
 *
 *
 * This class is a wrapper around an OpenGL texture object.
 *
 * @see [OpenGL Texture](https://www.khronos.org/registry/OpenGL-Refpages/es3.0/html/glBindTexture.xhtml)
 */
class Texture(width: Int, height: Int, target: Target, wrapMode: WrapMode) : Closeable {
    val textureId = IntArray(1)
    val target: Int

    /**
     * The target of the texture.
     *
     *
     * This is the value passed to `glBindTexture`.
     */
    enum class Target(val value: Int) {
        TEXTURE_2D(GLES30.GL_TEXTURE_2D), TEXTURE_EXTERNAL_OES(GLES11Ext.GL_TEXTURE_EXTERNAL_OES), TEXTURE_CUBE_MAP(
            GLES30.GL_TEXTURE_CUBE_MAP
        );
    }

    /** The wrap mode of the texture.  */
    enum class WrapMode(val value: Int) {
        CLAMP_TO_EDGE(GLES30.GL_CLAMP_TO_EDGE), REPEAT(GLES30.GL_REPEAT), MIRRORED_REPEAT(GLES30.GL_MIRRORED_REPEAT);

    }

    /**
     * Creates a new texture.
     *
     * @param width the width of the texture in pixels
     * @param height the height of the texture in pixels
     * @param target the target of the texture
     * @param wrapMode the wrap mode of the texture
     */
    init {
        this.target = target.value
        GLES30.glGenTextures(1, textureId, 0)
        GLES30.glBindTexture(this.target, textureId[0])
        GLES30.glTexParameteri(this.target, GLES30.GL_TEXTURE_WRAP_S, wrapMode.value)
        GLES30.glTexParameteri(this.target, GLES30.GL_TEXTURE_WRAP_T, wrapMode.value)
        GLES30.glTexParameteri(this.target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(this.target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        if (this.target == GLES30.GL_TEXTURE_2D) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA,
                width,
                height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null
            )
        }
    }

    override fun close() {
        if (textureId[0] != 0) {
            GLES30.glDeleteTextures(1, textureId, 0)
        }
    }
}