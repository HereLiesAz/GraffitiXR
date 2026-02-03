package com.hereliesaz.graffitixr.feature.ar.rendering

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a 3D metric grid centered at a specific pose (Anchor).
 * Useful for visualizing the wall plane and scale.
 */
class GridRenderer {

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var colorHandle: Int = 0

    private var vboId = 0
    private var numVertices = 0

    private val mvpMatrix = FloatArray(16)

    fun createOnGlThread() {
        val vertexShader = ShaderUtil.loadGLShader(TAG, VERTEX_SHADER, GLES30.GL_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, FRAGMENT_SHADER, GLES30.GL_FRAGMENT_SHADER)

        program = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vertexShader)
            GLES30.glAttachShader(it, fragmentShader)
            GLES30.glLinkProgram(it)
        }

        positionHandle = GLES30.glGetAttribLocation(program, "a_Position")
        mvpMatrixHandle = GLES30.glGetUniformLocation(program, "u_MvpMatrix")
        colorHandle = GLES30.glGetUniformLocation(program, "u_Color")

        generateGridGeometry()
    }

    private fun generateGridGeometry() {
        // Grid Params
        val size = 2.0f // 2 meters total width/height covering the anchor area
        val step = 0.1f // 10cm lines
        val vertices = ArrayList<Float>()

        val start = -size / 2
        val end = size / 2

        // Vertical lines
        var x = start
        while (x <= end + 0.001f) {
            vertices.add(x); vertices.add(start); vertices.add(0f)
            vertices.add(x); vertices.add(end); vertices.add(0f)
            x += step
        }

        // Horizontal lines
        var y = start
        while (y <= end + 0.001f) {
            vertices.add(start); vertices.add(y); vertices.add(0f)
            vertices.add(end); vertices.add(y); vertices.add(0f)
            y += step
        }

        numVertices = vertices.size / 3

        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(vertices.toFloatArray())
        buffer.position(0)

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, anchorMatrix: FloatArray) {
        if (vboId == 0) return

        GLES30.glUseProgram(program)

        // Calculate MVP: Proj * View * Model(Anchor)
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, anchorMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // White grid with 30% opacity
        GLES30.glUniform4f(colorHandle, 1.0f, 1.0f, 1.0f, 0.3f)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 12, 0)

        GLES30.glLineWidth(2.0f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, numVertices)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private const val TAG = "GridRenderer"

        private const val VERTEX_SHADER = """#version 300 es
            uniform mat4 u_MvpMatrix;
            layout(location = 0) in vec3 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            uniform vec4 u_Color;
            out vec4 FragColor;
            void main() {
                FragColor = u_Color;
            }
        """
    }
}