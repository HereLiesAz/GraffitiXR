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
    private var uScanActive: Int = 0
    private var uYaw: Int = 0
    private var uHalfFov: Int = 0
    private var uMask: Int = 0
    private var uDotSize: Int = 0
    private var hasTransformed = false
    private var diagFrame = 0

    // 36-sector scan-coverage mask (for the world-mapping "ink develop" reveal).
    private var maskTextureId = 0
    private val maskBytes = ByteBuffer.allocateDirect(SECTOR_COUNT).order(ByteOrder.nativeOrder())
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    fun createOnGlThread(context: Context) {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        // Scan-coverage mask texture (1D, 36 sectors), linear so coverage is smooth across sectors.
        val maskTex = IntArray(1)
        GLES30.glGenTextures(1, maskTex, 0)
        maskTextureId = maskTex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, SECTOR_COUNT, 1, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskBytes
        )

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
            quadTexCoordTransformed = ByteBuffer.allocateDirect(numVertices * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }

        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        backgroundProgram = GLES30.glCreateProgram()
        GLES30.glAttachShader(backgroundProgram, vertexShader)
        GLES30.glAttachShader(backgroundProgram, fragmentShader)
        GLES30.glLinkProgram(backgroundProgram)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(backgroundProgram, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(backgroundProgram)
            Log.e("BackgroundRenderer", "Link Error: $log")
            shaderLog = "linkFail:${log.take(60)}"
            GLES30.glDeleteProgram(backgroundProgram)
            backgroundProgram = 0
            return
        }
        shaderLog = "ok"

        quadPositionParam = GLES30.glGetAttribLocation(backgroundProgram, "a_Position")
        quadTexCoordParam = GLES30.glGetAttribLocation(backgroundProgram, "a_TexCoord")
        uTextureParam = GLES30.glGetUniformLocation(backgroundProgram, "u_Texture")
        uScanActive = GLES30.glGetUniformLocation(backgroundProgram, "u_ScanActive")
        uYaw = GLES30.glGetUniformLocation(backgroundProgram, "u_CameraYawRad")
        uHalfFov = GLES30.glGetUniformLocation(backgroundProgram, "u_HalfFovHRad")
        uMask = GLES30.glGetUniformLocation(backgroundProgram, "u_MaskTex")
        uDotSize = GLES30.glGetUniformLocation(backgroundProgram, "u_DotSizePx")
    }

    /** Push the 36-sector visited bitmask that drives the ink-develop reveal. */
    fun updateScanMask(visitedSectorsMask: Long) {
        if (maskTextureId == 0) return
        for (i in 0 until SECTOR_COUNT) {
            val visited = (visitedSectorsMask ushr i) and 1L
            maskBytes.put(i, if (visited == 1L) 255.toByte() else 0)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, SECTOR_COUNT, 1,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskBytes
        )
    }

    /**
     * Draws the camera background. When [scanActive], the unmapped parts of the view are rendered as a
     * gritty black-and-white newspaper halftone of the camera, and full colour/tone bleeds in
     * organically (like spreading ink) as each yaw sector is mapped. Otherwise it's a plain pass-through.
     */
    fun draw(frame: Frame, scanActive: Boolean = false) {
        diagFrame++
        if (backgroundProgram == 0) {
            if (diagFrame % 120 == 0) Log.w("ARDIAG", "BackgroundRenderer.draw: shader not ready -> camera black")
            return
        }
        if (diagFrame % 120 == 0) Log.i("ARDIAG", "BackgroundRenderer.draw: drawing camera quad (scanActive=$scanActive)")

        if (frame.hasDisplayGeometryChanged() || !hasTransformed) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadVertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoordTransformed
            )
            hasTransformed = true
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glUseProgram(backgroundProgram)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(textureTarget, textureId)
        GLES30.glUniform1i(uTextureParam, 0)

        var yaw = 0f
        var halfFov = 0.5f
        if (scanActive) {
            val cam = frame.camera
            cam.getViewMatrix(viewMatrix, 0)
            cam.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
            yaw = kotlin.math.atan2(-viewMatrix[2], -viewMatrix[10])
            halfFov = kotlin.math.atan(1.0 / projMatrix[0].toDouble()).toFloat()
        }
        GLES30.glUniform1f(uScanActive, if (scanActive) 1.0f else 0.0f)
        GLES30.glUniform1f(uYaw, yaw)
        GLES30.glUniform1f(uHalfFov, halfFov)
        GLES30.glUniform1f(uDotSize, DOT_SIZE_PX)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(uMask, 1)

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
        if (maskTextureId != 0) { GLES30.glDeleteTextures(1, intArrayOf(maskTextureId), 0); maskTextureId = 0 }
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
        private const val SECTOR_COUNT = 36
        private const val DOT_SIZE_PX = 6.0f // halftone cell size in screen pixels (newspaper grain)

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
            out vec2 v_NdcXy;
            void main() {
               gl_Position = a_Position;
               v_TexCoord = a_TexCoord;
               v_NdcXy = a_Position.xy;
            }
        """.trimIndent()

        // Camera pass-through, except during the AMBIENT scan: unmapped yaw sectors are shown as a
        // black-and-white newspaper halftone of the live camera, and the real full-colour frame bleeds
        // in organically (a value-noise threshold driven by per-sector coverage) so it reads as ink/tone
        // spreading into the photo — not an ink overlay. Mapped sectors are the untouched camera.
        private val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 v_TexCoord;
            in vec2 v_NdcXy;
            uniform samplerExternalOES u_Texture;
            uniform float u_ScanActive;
            uniform float u_CameraYawRad;
            uniform float u_HalfFovHRad;
            uniform sampler2D u_MaskTex;
            uniform float u_DotSizePx;
            out vec4 FragColor;

            const float TWO_PI = 6.28318530718;
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            void main() {
                // Plain camera pass-through. The screen-space halftone->colour "develop" reveal was
                // removed: it was a flat 2D effect (same failing as the pink fog). A real scan indicator
                // must spread on the actual 3D surfaces being mapped, not across the whole frame.
                FragColor = texture(u_Texture, v_TexCoord);
            }
        """.trimIndent()
    }
}
