package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class PlaneRenderer(context: Context, private val assets: Context) {
    private val TAG = "PlaneRenderer"
    private var planeProgram = 0
    private var vertexBuffer: FloatBuffer? = null
    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)
    private var positionAttribute = 0
    private var modelViewProjectionUniform = 0
    private var textureUniform = 0
    private var colorUniform = 0
    private var gridControlUniform = 0
    private var planePolygon: FloatBuffer? = null

    fun createOnGlThread(context: Context) {
        val vertexShader = ShaderUtil.loadGLShader(TAG, VERTEX_SHADER, GLES30.GL_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, FRAGMENT_SHADER, GLES30.GL_FRAGMENT_SHADER)
        planeProgram = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)
        }
        positionAttribute = GLES30.glGetAttribLocation(planeProgram, "a_Position")
        modelViewProjectionUniform = GLES30.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
        textureUniform = GLES30.glGetUniformLocation(planeProgram, "u_Texture")
        colorUniform = GLES30.glGetUniformLocation(planeProgram, "u_Color")
        gridControlUniform = GLES30.glGetUniformLocation(planeProgram, "u_GridControl")
    }

    private fun updatePlaneData(plane: Plane) {
        val polygon = plane.polygon
        if (polygon == null) {
            planePolygon = null
            return
        }
        planePolygon = polygon
        if (vertexBuffer == null || vertexBuffer!!.capacity() < polygon.limit()) {
            val newCapacity = polygon.limit()
            val bb = ByteBuffer.allocateDirect(newCapacity * 4)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
        }
        vertexBuffer!!.clear()
        vertexBuffer!!.put(polygon)
        vertexBuffer!!.flip()
    }

    fun drawPlanes(
        planes: Collection<Plane>,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        gridAlpha: Float = 1.0f,
        gridLineWidth: Float = 0.02f,
        outlineWidth: Float = 5.0f
    ): Boolean {
        var hasDrawn = false
        var isStateSet = false
        val camFwdX = -viewMatrix[2]
        val camFwdY = -viewMatrix[6]
        val camFwdZ = -viewMatrix[10]

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }
            if (!isStateSet) {
                GLES30.glUseProgram(planeProgram)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
                isStateSet = true
            }
            updatePlaneData(plane)
            plane.centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
            val normX = modelMatrix[4]
            val normY = modelMatrix[5]
            val normZ = modelMatrix[6]
            val dot = camFwdX * normX + camFwdY * normY + camFwdZ * normZ
            val absDot = kotlin.math.abs(dot)
            val (r, g, b) = if (absDot > 0.7f) {
                Triple(0.0f, 1.0f, 0.0f)
            } else {
                Triple(0.0f, 1.0f, 1.0f)
            }
            GLES30.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
            GLES30.glVertexAttribPointer(positionAttribute, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
            GLES30.glEnableVertexAttribArray(positionAttribute)
            if (gridAlpha > 0.0f) {
                GLES30.glUniform2f(gridControlUniform, gridLineWidth, gridAlpha)
                GLES30.glUniform4f(colorUniform, r, g, b, 0.0f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, plane.polygon.limit() / 2)
            }
            if (outlineWidth > 0.0f) {
                GLES30.glLineWidth(outlineWidth)
                GLES30.glUniform4f(colorUniform, r, g, b, 1.0f)
                GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, plane.polygon.limit() / 2)
            }
            GLES30.glDisableVertexAttribArray(positionAttribute)
            hasDrawn = true
        }
        if (isStateSet) {
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        return hasDrawn
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            uniform mat4 u_ModelViewProjection;
            layout(location = 0) in vec2 a_Position;
            out vec2 v_TexCoord;
            void main() {
               v_TexCoord = a_Position;
               gl_Position = u_ModelViewProjection * vec4(a_Position.x, 0.0, a_Position.y, 1.0);
            }
        """
        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            uniform vec4 u_Color;
            uniform vec2 u_GridControl;
            in vec2 v_TexCoord;
            out vec4 FragColor;
            void main() {
                float gridWidth = 0.1524;
                float lineThickness = u_GridControl.x;
                vec2 grid = step(gridWidth - lineThickness, mod(abs(v_TexCoord), gridWidth));
                float isLine = max(grid.x, grid.y);
                float alpha = mix(u_Color.a, 1.0, isLine);
                alpha *= u_GridControl.y;
                FragColor = vec4(u_Color.rgb, alpha);
            }
        """
    }
}
