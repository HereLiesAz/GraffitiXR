package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import com.hereliesaz.graffitixr.common.util.GlReleasable
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Offscreen colour+depth framebuffer that caches the world-locked perception layers (voxel splats,
 * coverage mask, plane grids, point cloud, mesh, feature points) so they can be REDRAWN at a
 * throttled rate while the camera background and the gesture-driven overlay keep rendering every
 * frame.
 *
 * Why this exists: perception is world-anchored, so between camera-pose updates a redraw produces a
 * pixel-identical image — pure GPU/battery waste. But the GL surface clears every frame, so simply
 * skipping the perception passes would make them flicker. Caching them here and compositing the
 * cached texture every frame gives smooth gestures + throttled perception + no flicker.
 *
 * Ownership of the refresh cadence stays with [ArRenderer]; this class only holds the buffer and the
 * full-screen composite. The composite assumes mostly-opaque perception (debug layers); the coverage
 * mask's sub-unit alpha can blend slightly differently than when drawn directly — acceptable for the
 * throttle probe, refine to premultiplied alpha later if it reads wrong on device.
 */
class PerceptionFbo : GlReleasable {
    private var fbo = 0
    private var colorTex = 0
    private var depthRbo = 0
    private var width = 0
    private var height = 0

    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private var quadVbo = 0

    /** True once the composite program + quad are built. The FBO attachment is sized lazily. */
    var ready = false
        private set

    fun createOnGlThread(context: Context) {
        val vs = """
            attribute vec2 a_Pos;
            attribute vec2 a_Uv;
            varying vec2 v_Uv;
            void main() {
                v_Uv = a_Uv;
                gl_Position = vec4(a_Pos, 0.0, 1.0);
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            uniform sampler2D u_Tex;
            varying vec2 v_Uv;
            void main() {
                gl_FragColor = texture2D(u_Tex, v_Uv);
            }
        """.trimIndent()
        val v = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, vs)
        val f = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, fs)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
        }
        aPos = GLES20.glGetAttribLocation(program, "a_Pos")
        aUv = GLES20.glGetAttribLocation(program, "a_Uv")
        uTex = GLES20.glGetUniformLocation(program, "u_Tex")

        // Full-screen triangle strip: x, y, u, v (NDC quad).
        val quad = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        val bb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
        val fbuf: FloatBuffer = bb.asFloatBuffer().apply { put(quad); position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, fbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        ready = true
    }

    /** (Re)allocate the colour texture + depth renderbuffer when the surface size changes. */
    fun resize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == width && h == height && fbo != 0)) return
        releaseFboOnly()
        width = w
        height = h

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        colorTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        val rbo = IntArray(1)
        GLES20.glGenRenderbuffers(1, rbo, 0)
        depthRbo = rbo[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRbo)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)

        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        fbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthRbo)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** True when a valid attachment exists at the current size. */
    fun isSized(): Boolean = fbo != 0 && width > 0 && height > 0

    /** Bind the FBO and clear it transparent so only perception pixels carry alpha. */
    fun bindForRender() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    }

    /** Restore the default framebuffer + the surface viewport. */
    fun unbind(surfaceW: Int, surfaceH: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
    }

    /** Composite the cached perception texture over the current framebuffer (alpha-blended). */
    fun composite() {
        if (!ready || colorTex == 0) return
        val depthWasOn = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (depthWasOn) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun releaseFboOnly() {
        if (fbo != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        if (colorTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(colorTex), 0); colorTex = 0 }
        if (depthRbo != 0) { GLES20.glDeleteRenderbuffers(1, intArrayOf(depthRbo), 0); depthRbo = 0 }
        width = 0; height = 0
    }

    override fun release() {
        releaseFboOnly()
        if (quadVbo != 0) { GLES20.glDeleteBuffers(1, intArrayOf(quadVbo), 0); quadVbo = 0 }
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        ready = false
    }

    companion object { private const val TAG = "PerceptionFbo" }
}
