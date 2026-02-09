package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20

class GridRenderer(private val context: Context) {
    private var programId = 0

    fun createOnGlThread() {
        val vertexShader = ShaderUtil.loadGLShader("shaders/grid_vertex.glsl", GLES20.GL_VERTEX_SHADER, context)
        val fragmentShader = ShaderUtil.loadGLShader("shaders/grid_fragment.glsl", GLES20.GL_FRAGMENT_SHADER, context)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        // Draw logic
    }
}