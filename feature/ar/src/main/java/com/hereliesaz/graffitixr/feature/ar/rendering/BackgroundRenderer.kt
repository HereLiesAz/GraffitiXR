package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.hereliesaz.graffitixr.common.util.GlReleasable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class BackgroundRenderer : GlReleasable {
    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoord: FloatBuffer
    private lateinit var quadTexCoordTransformed: FloatBuffer

    private var backgroundProgram: Int = 0
    private var textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    var textureId: Int = -1
        private set

    /** True once the camera-background shader program has compiled+linked. If false, draw() can't
     *  paint the camera and the passthrough is black. */
    val isProgramReady: Boolean get() = backgroundProgram != 0
    /** Last shader compile/link diagnostic, surfaced on-screen to catch driver-specific shader fails
     *  (e.g. a ROM whose GL driver lacks GL_OES_EGL_image_external_essl3). */
    var shaderLog: String = "uninit"
        private set

    private var quadPositionParam: Int = 0
    private var quadTexCoordParam: Int = 0
    private var uTextureParam: Int = 0
    /** @Volatile: cleared off the GL thread by [invalidateDisplayGeometry] when a new session is
     *  attached; read and written on the GL thread by [draw]. */
    @Volatile private var hasTransformed = false
    private var diagFrame = 0
    private var badTransformFrame = 0
    /** Last UV set that passed [transformedTexCoordsAreSane], restored whenever a fresh transform is
     *  rejected. Starts as the identity mapping so the very first rejection still shows the frame. */
    private val lastGoodTexCoords = QUAD_TEXCOORDS.copyOf()

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val numVertices = 4
        if (!::quadVertices.isInitialized) {
            quadVertices = ByteBuffer.allocateDirect(numVertices * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_COORDS); position(0) }
        }
        if (!::quadTexCoord.isInitialized) {
            quadTexCoord = ByteBuffer.allocateDirect(numVertices * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_TEXCOORDS); position(0) }
        }
        if (!::quadTexCoordTransformed.isInitialized) {
            // Seeded with the identity UVs, NOT left zero-filled. A zero-filled buffer makes all four
            // vertices sample texel (0,0), so the camera renders as one texel stretched over the whole
            // screen — indistinguishable from a broken camera. The identity mapping is the wrong
            // orientation on most devices but still shows the whole frame, so any window where the
            // ARCore transform is missing or rejected degrades to a readable preview.
            quadTexCoordTransformed = ByteBuffer.allocateDirect(numVertices * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_TEXCOORDS); position(0) }
        }

        // Try ESSL3 (matches the rest of the pipeline). If this driver lacks the
        // GL_OES_EGL_image_external_essl3 extension (some custom ROMs / Mesa Turnip do), that shader
        // won't compile and the camera passthrough would be black — so fall back to the far more widely
        // supported ESSL1 (#version 100 / GL_OES_EGL_image_external) external-texture path.
        backgroundProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (backgroundProgram == 0) {
            val essl3Err = shaderLog
            backgroundProgram = buildProgram(VERTEX_SHADER_ES1, FRAGMENT_SHADER_ES1)
            shaderLog = if (backgroundProgram != 0) "ok(essl1 fallback)" else "bothFail[essl3:$essl3Err|essl1:$shaderLog]"
        }
        if (backgroundProgram == 0) return

        quadPositionParam = GLES30.glGetAttribLocation(backgroundProgram, "a_Position")
        quadTexCoordParam = GLES30.glGetAttribLocation(backgroundProgram, "a_TexCoord")
        uTextureParam = GLES30.glGetUniformLocation(backgroundProgram, "u_Texture")
    }

    /**
     * Drops the cached camera UV transform so [draw] recomputes it on the next frame.
     *
     * Must be called whenever a different [com.google.ar.core.Frame] source takes over — i.e. when a
     * new ARCore session is attached — because the cached UVs describe the previous session's camera
     * image and display geometry. Safe to call off the GL thread.
     */
    fun invalidateDisplayGeometry() {
        hasTransformed = false
    }

    /**
     * True when the transformed UVs still cover a meaningful span of the camera image in both axes.
     *
     * A correct full-screen transform always spans most of the texture (ARCore centre-crops the camera
     * image to the viewport, so the smaller axis never drops far below half). Anything near-degenerate
     * — or non-finite — means the transform was taken against geometry ARCore hasn't been given yet,
     * and drawing with it magnifies a few texels across the whole screen.
     */
    private fun transformedTexCoordsAreSane(): Boolean {
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (i in 0 until 4) {
            // Absolute gets: transformCoordinates2d may leave the buffer's position anywhere.
            val u = quadTexCoordTransformed.get(i * 2)
            val v = quadTexCoordTransformed.get(i * 2 + 1)
            if (!u.isFinite() || !v.isFinite()) return false
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        return (maxU - minU) >= MIN_UV_SPAN && (maxV - minV) >= MIN_UV_SPAN
    }

    /**
     * Draws the camera background as a plain pass-through. (A screen-space halftone "ink develop" scan
     * reveal used to live here; it was removed in 425ed4012 as a flat 2D effect — a real scan
     * indicator has to spread on the mapped 3D surfaces — so there is no scan uniform or mask texture.)
     */
    fun draw(frame: Frame) {
        diagFrame++
        if (backgroundProgram == 0) {
            if (diagFrame % 120 == 0) Log.w("ARDIAG", "BackgroundRenderer.draw: shader not ready -> camera black")
            return
        }
        if (diagFrame % 120 == 0) Log.i("ARDIAG", "BackgroundRenderer.draw: drawing camera quad")

        if (frame.hasDisplayGeometryChanged() || !hasTransformed) {
            // Rewind both buffers first: transformCoordinates2d reads/writes from the current position,
            // and the transform can now run on more than the first frame.
            quadVertices.position(0)
            quadTexCoordTransformed.position(0)
            val transformed = try {
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    quadVertices,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    quadTexCoordTransformed
                )
                // Only accept a result that actually maps the quad across the camera image. A transform
                // taken against a session that hasn't been given the real display geometry collapses the
                // full-screen quad onto a sliver (or a single point) of the texture, which draws as the
                // camera magnified to the point of showing one pixel.
                transformedTexCoordsAreSane()
            } catch (t: Throwable) {
                // This now runs on more than just the first frame, so a throw here must not take the GL
                // thread (and with it the whole camera passthrough) down.
                Log.w("ARDIAG", "BackgroundRenderer: camera UV transform threw", t)
                false
            }
            quadTexCoordTransformed.position(0)
            if (transformed) {
                quadTexCoordTransformed.get(lastGoodTexCoords)
                hasTransformed = true
            } else {
                // Fall back to the last accepted UVs (identity until one is accepted) and retry next
                // frame, by which time DisplayRotationHelper will have pushed the viewport.
                quadTexCoordTransformed.put(lastGoodTexCoords)
                if (badTransformFrame++ % 120 == 0) {
                    Log.w("ARDIAG", "BackgroundRenderer: camera UV transform rejected -> last good UVs")
                }
            }
            quadTexCoordTransformed.position(0)
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glUseProgram(backgroundProgram)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glUniform1i(uTextureParam, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        quadVertices.position(0)
        GLES30.glVertexAttribPointer(quadPositionParam, 2, GLES30.GL_FLOAT, false, 0, quadVertices)
        GLES30.glEnableVertexAttribArray(quadPositionParam)

        quadTexCoordTransformed.position(0)
        GLES30.glVertexAttribPointer(quadTexCoordParam, 2, GLES30.GL_FLOAT, false, 0, quadTexCoordTransformed)
        GLES30.glEnableVertexAttribArray(quadTexCoordParam)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(quadPositionParam)
        GLES30.glDisableVertexAttribArray(quadTexCoordParam)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /**
     * Deletes the camera-background shader program and the external-OES texture.
     * Idempotent; must run on the GL thread.
     */
    override fun release() {
        if (backgroundProgram != 0) { GLES30.glDeleteProgram(backgroundProgram); backgroundProgram = 0 }
        if (textureId > 0) { GLES30.glDeleteTextures(1, intArrayOf(textureId), 0); textureId = -1 }
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = loadShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        if (vs == 0) return 0
        // Compile fs only after vs succeeds, and free vs if fs fails, so a failed build (e.g. ESSL3
        // before the ESSL1 fallback) never leaks an orphaned shader object.
        val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return 0
        }
        val prog = GLES30.glCreateProgram()
        if (prog == 0) {
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return 0
        }
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        // Once attached+linked the shader objects are no longer needed; flag for deletion (the driver
        // frees them when they're detached, i.e. when the program is deleted).
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            Log.e("BackgroundRenderer", "Link Error: $log")
            shaderLog = "linkFail:${log.take(60)}"
            GLES30.glDeleteProgram(prog)
            return 0
        }
        shaderLog = "ok"
        return prog
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            Log.e("BackgroundRenderer", "Shader Compile Error: $log")
            shaderLog = "compileFail(${if (type == GLES30.GL_VERTEX_SHADER) "vs" else "fs"}):${log.take(60)}"
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        /** Minimum span, per axis, a valid camera UV transform must cover. A real one covers ~0.5–1.0;
         *  this floor only catches collapsed transforms, never a legitimate crop. */
        private const val MIN_UV_SPAN = 0.1f

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, -1.0f,
            +1.0f, +1.0f
        )

        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )

        private val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec4 a_Position;
            layout(location = 1) in vec2 a_TexCoord;
            out vec2 v_TexCoord;
            void main() {
               gl_Position = a_Position;
               v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        // Plain camera pass-through (see draw()).
        private val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            out vec4 FragColor;
            void main() {
                FragColor = texture(u_Texture, v_TexCoord);
            }
        """.trimIndent()

        // ESSL1 (#version 100) fallback for drivers without GL_OES_EGL_image_external_essl3. Same
        // plain camera pass-through.
        private val VERTEX_SHADER_ES1 = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
               gl_Position = a_Position;
               v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_ES1 = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
               gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """.trimIndent()
    }
}
