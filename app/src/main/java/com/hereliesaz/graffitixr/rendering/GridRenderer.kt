package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a static 3D grid to visualize the target plane.
 */
class GridRenderer {
    private var program = 0
    private var positionParam = 0
    private var mvpParam = 0
    private var colorParam = 0
    private var vertexBuffer: FloatBuffer? = null
    private var numVertices = 0

    fun createOnGlThread() {
        val vertexShader = ShaderUtil.loadGLShader("Grid", V_SHADER, GLES20.GL_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader("Grid", F_SHADER, GLES20.GL_FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        positionParam = GLES20.glGetAttribLocation(program, "a_Position")
        mvpParam = GLES20.glGetUniformLocation(program, "u_MVP")
        colorParam = GLES20.glGetUniformLocation(program, "u_Color")

        generateGridGeometry()
    }

    private fun generateGridGeometry() {
        val size = 1.0f // 1 meter
        val steps = 10
        val stepSize = size / steps
        val vertices = ArrayList<Float>()

        for (i in 0..steps) {
            val p = -size/2 + i * stepSize
            // Horizontal line
            vertices.add(-size/2); vertices.add(p); vertices.add(0f)
            vertices.add(size/2); vertices.add(p); vertices.add(0f)
            // Vertical line
            vertices.add(p); vertices.add(-size/2); vertices.add(0f)
            vertices.add(p); vertices.add(size/2); vertices.add(0f)
        }

        numVertices = vertices.size / 3
        val bb = ByteBuffer.allocateDirect(vertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer?.put(vertices.toFloatArray())
        vertexBuffer?.position(0)
    }

    fun draw(viewMtx: FloatArray, projMtx: FloatArray, modelMtx: FloatArray) {
        GLES20.glUseProgram(program)

        val mvp = FloatArray(16)
        val mv = FloatArray(16)
        Matrix.multiplyMM(mv, 0, viewMtx, 0, modelMtx, 0)
        Matrix.multiplyMM(mvp, 0, projMtx, 0, mv, 0)

        GLES20.glUniformMatrix4fv(mvpParam, 1, false, mvp, 0)
        GLES20.glUniform4f(colorParam, 0f, 1f, 1f, 0.5f) // Cyan, semi-transparent

        GLES20.glEnableVertexAttribArray(positionParam)
        GLES20.glVertexAttribPointer(positionParam, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glLineWidth(2.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, numVertices)

        GLES20.glDisableVertexAttribArray(positionParam)
    }

    companion object {
        const val V_SHADER = "uniform mat4 u_MVP; attribute vec4 a_Position; void main() { gl_Position = u_MVP * a_Position; }"
        const val F_SHADER = "precision mediump float; uniform vec4 u_Color; void main() { gl_FragColor = u_Color; }"
    }
}