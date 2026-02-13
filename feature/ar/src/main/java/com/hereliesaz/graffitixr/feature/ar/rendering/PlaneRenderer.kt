package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class PlaneRenderer {
    private var planeProgram = 0

    // Uniform Locations
    private var planeModelUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var gridControlUniform = 0

    // Buffers
    private val vertexBuffer = ByteBuffer.allocateDirect(1000 * 4) // Reusable buffer (Float = 4 bytes)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    fun createOnGlThread(context: Context) {
        val vertexShaderCode = context.assets.open("shaders/plane.vert").bufferedReader().use { it.readText() }
        val fragmentShaderCode = context.assets.open("shaders/plane.frag").bufferedReader().use { it.readText() }

        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val passthroughShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        planeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(planeProgram, vertexShader)
        GLES20.glAttachShader(planeProgram, passthroughShader)
        GLES20.glLinkProgram(planeProgram)

        planeModelUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModel")
        planeModelViewProjectionUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModelViewProjection")
        gridControlUniform = GLES20.glGetUniformLocation(planeProgram, "u_gridControl")
    }

    fun drawPlanes(session: Session, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
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

        val posAttr = GLES20.glGetAttribLocation(planeProgram, "a_Position")
        GLES20.glEnableVertexAttribArray(posAttr)
        GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, count)
        GLES20.glDisableVertexAttribArray(posAttr)
    }

    companion object {
        private const val TAG = "PlaneRenderer"
    }
}