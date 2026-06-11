package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.common.util.GlReleasable
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class PlaneRenderer : GlReleasable {
    private var planeProgram = 0

    private var planeModelUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var gridControlUniform = 0
    private var planeColorUniform = 0
    private var isOutlineUniform = 0
    private var gridModeUniform = 0
    private var developUniform = 0
    private var firstDrawNs = -1L // for the ink-spread develop ramp

    private var vertexBuffer = ByteBuffer.allocateDirect(1000 * 4) // Reusable buffer (Float = 4 bytes), grown on demand
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    fun createOnGlThread(context: Context) {
        val vertexShaderCode = """
            uniform mat4 u_PlaneModel;
            uniform mat4 u_PlaneModelViewProjection;
            attribute vec2 a_PositionXZ;
            varying vec2 v_PosXZ;
            void main() {
                v_PosXZ = a_PositionXZ; // plane-local metres — anchored to the surface
                gl_Position = u_PlaneModelViewProjection * vec4(a_PositionXZ.x, 0.0, a_PositionXZ.y, 1.0);
            }
        """.trimIndent()

        // Ink-develop fill: the colour is "soaked" into the actual surface as a value-noise texture in
        // plane-local space, so it stays put on the wall/floor as the camera moves (unlike the old
        // screen-space reveal). It spreads in as u_Develop rises. Hue still carries the match meaning.
        // u_GridMode = 1 (debug perception view) replaces the ink fill with a metric grid in
        // plane-local metres — 0.25 m cells with the plane's local X/Z axes emphasised — so the
        // plane's ORIENTATION is readable at a glance, not just its silhouette.
        val fragmentShaderCode = """
            precision mediump float;
            uniform vec4 u_Color;
            uniform int u_IsOutline;
            uniform int u_GridMode;
            uniform float u_Develop;
            varying vec2 v_PosXZ;
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }
            void main() {
                if (u_IsOutline == 1) {
                    gl_FragColor = vec4(u_Color.rgb, 0.9);
                } else if (u_GridMode == 1) {
                    vec2 f = fract(v_PosXZ * 4.0);
                    vec2 d = min(f, 1.0 - f);
                    float line = 1.0 - smoothstep(0.0, 0.08, min(d.x, d.y));
                    float axis = 1.0 - smoothstep(0.0, 0.03, min(abs(v_PosXZ.x), abs(v_PosXZ.y)));
                    // Lines lifted toward white so the grid reads over the camera and any hue.
                    vec3 lineCol = mix(u_Color.rgb, vec3(1.0), 0.55);
                    float a = max(line * 0.85, axis);
                    gl_FragColor = vec4(lineCol, max(a, 0.05));
                } else {
                    float n = vnoise(v_PosXZ * 18.0);
                    float ink = smoothstep(n - 0.18, n + 0.18, u_Develop);
                    gl_FragColor = vec4(u_Color.rgb, ink * 0.5);
                }
            }
        """.trimIndent()

        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val passthroughShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        planeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(planeProgram, vertexShader)
        GLES20.glAttachShader(planeProgram, passthroughShader)
        GLES20.glLinkProgram(planeProgram)

        planeModelUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModel")
        planeModelViewProjectionUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModelViewProjection")
        gridControlUniform = GLES20.glGetUniformLocation(planeProgram, "u_gridControl")
        planeColorUniform = GLES20.glGetUniformLocation(planeProgram, "u_Color")
        isOutlineUniform = GLES20.glGetUniformLocation(planeProgram, "u_IsOutline")
        gridModeUniform = GLES20.glGetUniformLocation(planeProgram, "u_GridMode")
        developUniform = GLES20.glGetUniformLocation(planeProgram, "u_Develop")
    }

    /**
     * @param gridMode When true (debug perception view) planes render as a metric grid with
     * emphasised local axes instead of the ink-develop fill, so their orientation is visible.
     */
    fun drawPlanes(session: Session, viewMatrix: FloatArray, projectionMatrix: FloatArray, cameraPose: Pose, gridMode: Boolean = false) {
        val planes = session.getAllTrackables(Plane::class.java)

        GLES20.glUseProgram(planeProgram)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniform1f(gridControlUniform, 1.0f)
        GLES20.glUniform1i(gridModeUniform, if (gridMode) 1 else 0)

        // Ink-spread progress: ramp 0->1 over ~1.5 s from the first drawn frame, so the colour visibly
        // soaks into the surfaces as the scan gets going (then holds).
        if (firstDrawNs < 0L) firstDrawNs = System.nanoTime()
        val develop = ((System.nanoTime() - firstDrawNs) / 1_500_000_000.0f).coerceIn(0f, 1f)
        GLES20.glUniform1f(developUniform, develop)

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }

            val color = calculatePlaneColor(plane, cameraPose)
            GLES20.glUniform4fv(planeColorUniform, 1, color, 0)

            drawPlane(plane, viewMatrix, projectionMatrix)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    private fun drawPlane(plane: Plane, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val polygon = plane.polygon
        if (polygon.remaining() > vertexBuffer.capacity()) {
            // Large/merged plane polygons exceed the initial 500-vertex buffer; grow to fit
            // instead of throwing BufferOverflowException out of the GL frame.
            vertexBuffer = ByteBuffer.allocateDirect(polygon.remaining() * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        vertexBuffer.clear()
        vertexBuffer.put(polygon)
        vertexBuffer.flip()

        val count = vertexBuffer.limit() / 2

        plane.centerPose.toMatrix(modelMatrix, 0)

        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(planeModelUniform, 1, false, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(planeModelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

        val posAttr = GLES20.glGetAttribLocation(planeProgram, "a_PositionXZ")
        GLES20.glEnableVertexAttribArray(posAttr)
        GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Draw Fill
        GLES20.glUniform1i(isOutlineUniform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, count)

        // Draw Outline
        GLES20.glUniform1i(isOutlineUniform, 1)
        GLES20.glLineWidth(5.0f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, count)

        GLES20.glDisableVertexAttribArray(posAttr)
    }

    /**
     * Deletes the plane shader program. The vertex buffer is a plain direct buffer
     * (no GL buffer object) and is reclaimed by GC. Idempotent; must run on the GL thread.
     */
    override fun release() {
        if (planeProgram != 0) { GLES20.glDeleteProgram(planeProgram); planeProgram = 0 }
    }

    enum class PlaneMatchResult {
        MATCH,      // Green: Parallel and close enough
        NO_MATCH,   // Pink: Perpendicular
        SUBOPTIMAL  // Cyan: Parallel but too far/suboptimal angle
    }

    fun classifyPlane(plane: Plane, cameraPose: Pose): PlaneMatchResult {
        val planeNormal = FloatArray(4)
        plane.centerPose.getTransformedAxis(1, 1.0f, planeNormal, 0)

        val cameraForward = FloatArray(4)
        cameraPose.getTransformedAxis(2, -1.0f, cameraForward, 0)

        val dot = planeNormal[0] * cameraForward[0] + planeNormal[1] * cameraForward[1] + planeNormal[2] * cameraForward[2]
        val absDot = abs(dot)

        if (absDot < 0.3f) {
            return PlaneMatchResult.NO_MATCH
        } else if (absDot > 0.65f) {
            val dist = calculateDistance(plane.centerPose, cameraPose)
            return if (dist < 3.0f) {
                PlaneMatchResult.MATCH
            } else {
                PlaneMatchResult.SUBOPTIMAL
            }
        } else {
            return PlaneMatchResult.SUBOPTIMAL
        }
    }

    fun calculatePlaneColor(plane: Plane, cameraPose: Pose): FloatArray {
        return when (classifyPlane(plane, cameraPose)) {
            PlaneMatchResult.MATCH -> floatArrayOf(0.0f, 1.0f, 0.0f, 0.3f)
            PlaneMatchResult.NO_MATCH -> floatArrayOf(1.0f, 0.4f, 0.7f, 0.3f)
            PlaneMatchResult.SUBOPTIMAL -> floatArrayOf(0.0f, 1.0f, 1.0f, 0.3f)
        }
    }

    private fun calculateDistance(p1: Pose, p2: Pose): Float {
        val dx = p1.tx() - p2.tx()
        val dy = p1.ty() - p2.ty()
        val dz = p1.tz() - p2.tz()
        return sqrt(dx*dx + dy*dy + dz*dz)
    }

    companion object {
        private const val TAG = "PlaneRenderer"
    }
}