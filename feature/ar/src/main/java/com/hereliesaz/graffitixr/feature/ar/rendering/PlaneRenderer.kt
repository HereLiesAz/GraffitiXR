package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class PlaneRenderer {
    private var planeProgram = 0

    private var planeModelUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var gridControlUniform = 0
    private var planeColorUniform = 0
    private var isOutlineUniform = 0

    private val vertexBuffer = ByteBuffer.allocateDirect(1000 * 4) // Reusable buffer (Float = 4 bytes)
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
            void main() {
                gl_Position = u_PlaneModelViewProjection * vec4(a_PositionXZ.x, 0.0, a_PositionXZ.y, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            uniform vec4 u_Color;
            uniform int u_IsOutline;
            void main() {
                if (u_IsOutline == 1) {
                    gl_FragColor = vec4(u_Color.rgb, 1.0);
                } else {
                    gl_FragColor = u_Color;
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
    }

    fun drawPlanes(session: Session, viewMatrix: FloatArray, projectionMatrix: FloatArray, cameraPose: Pose) {
        val planes = session.getAllTrackables(Plane::class.java)

        GLES20.glUseProgram(planeProgram)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniform1f(gridControlUniform, 1.0f)

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
        } else if (absDot > 0.95f) {
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