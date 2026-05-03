package com.hereliesaz.graffitixr.feature.ar.rendering

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a translucent hot-pink "fog of war" over the camera feed during the
 * AMBIENT scan phase. Each pixel column maps to an absolute yaw direction; the
 * 36-sector visited bitmask drives a 1D texture sampled with linear filtering
 * so the fog clears smoothly as the user pans across visited sectors.
 *
 * Single fullscreen quad, no geometry, no depth interaction. Mask is updated
 * per frame from the SLAM-derived sector mask in [com.hereliesaz.graffitixr.feature.ar.ArViewModel].
 */
class ScanFogRenderer {
    private var program = 0
    private var aPosition = 0
    private var uYaw = 0
    private var uHalfFov = 0
    private var uMask = 0
    private var uColor = 0
    private var uAlpha = 0
    private var maskTextureId = 0

    private lateinit var quadVertices: FloatBuffer
    private val maskBytes = ByteBuffer.allocateDirect(SECTOR_COUNT)
        .order(ByteOrder.nativeOrder())

    fun createOnGlThread() {
        quadVertices = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(QUAD_COORDS); position(0) }

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        maskTextureId = texs[0]
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

        val vs = compile(GLES30.GL_VERTEX_SHADER, VS)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FS)
        if (vs == 0 || fs == 0) return

        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val link = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            Log.e(TAG, "Link error: " + GLES30.glGetProgramInfoLog(program))
            GLES30.glDeleteProgram(program)
            program = 0
            return
        }

        aPosition = GLES30.glGetAttribLocation(program, "a_Position")
        uYaw = GLES30.glGetUniformLocation(program, "u_CameraYawRad")
        uHalfFov = GLES30.glGetUniformLocation(program, "u_HalfFovHRad")
        uMask = GLES30.glGetUniformLocation(program, "u_MaskTex")
        uColor = GLES30.glGetUniformLocation(program, "u_Color")
        uAlpha = GLES30.glGetUniformLocation(program, "u_Alpha")
    }

    fun updateMask(visitedSectorsMask: Long) {
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

    fun draw(cameraYawRad: Float, halfFovHRad: Float) {
        if (program == 0) return

        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uYaw, cameraYawRad)
        GLES30.glUniform1f(uHalfFov, halfFovHRad)
        GLES30.glUniform3f(uColor, FOG_COLOR[0], FOG_COLOR[1], FOG_COLOR[2])
        GLES30.glUniform1f(uAlpha, FOG_ALPHA)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(uMask, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        quadVertices.position(0)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, quadVertices)
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosition)

        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES30.glGetShaderInfoLog(s))
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    companion object {
        private const val TAG = "ScanFogRenderer"
        private const val SECTOR_COUNT = 36
        // Hot pink (#FF1493) — vivid against camera feed and the cyan UI accent.
        private val FOG_COLOR = floatArrayOf(1.0f, 0.078f, 0.576f)
        private const val FOG_ALPHA = 0.35f

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, -1.0f,
            +1.0f, +1.0f,
        )

        private val VS = """#version 300 es
            layout(location = 0) in vec4 a_Position;
            out vec2 v_NdcXy;
            void main() {
                gl_Position = a_Position;
                v_NdcXy = a_Position.xy;
            }
        """.trimIndent()

        // Maps each pixel column to its absolute world-yaw via the camera's
        // horizontal half-FOV (using atan for accuracy near the edges), then
        // samples the 36-sector visited mask and fades the fog smoothly.
        private val FS = """#version 300 es
            precision mediump float;
            in vec2 v_NdcXy;
            uniform float u_CameraYawRad;
            uniform float u_HalfFovHRad;
            uniform sampler2D u_MaskTex;
            uniform vec3 u_Color;
            uniform float u_Alpha;
            out vec4 FragColor;

            const float TWO_PI = 6.28318530718;

            void main() {
                float yawOffset = atan(v_NdcXy.x * tan(u_HalfFovHRad));
                float yaw = u_CameraYawRad + yawOffset;
                float u = fract(yaw / TWO_PI);
                if (u < 0.0) u += 1.0;
                float visited = texture(u_MaskTex, vec2(u, 0.5)).r;
                float verticalAttn = 1.0 - 0.35 * abs(v_NdcXy.y);
                float alpha = (1.0 - visited) * u_Alpha * verticalAttn;
                FragColor = vec4(u_Color, alpha);
            }
        """.trimIndent()
    }
}
