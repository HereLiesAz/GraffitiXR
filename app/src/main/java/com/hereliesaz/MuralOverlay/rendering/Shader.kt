package com.hereliesaz.MuralOverlay.rendering

import android.content.res.AssetManager
import android.opengl.GLES30
import java.io.Closeable
import java.io.InputStreamReader

class Shader(
    assets: AssetManager,
    vertexShaderFileName: String,
    fragmentShaderFileName: String,
    defines: Map<String, String>?
) : Closeable {
    private var programId = 0

    init {
        val definesCode = defines?.entries?.joinToString("\n") { "#define ${it.key} ${it.value}" } ?: ""
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, definesCode + "\n" + readTextFileFromAssets(assets, vertexShaderFileName))
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, definesCode + "\n" + readTextFileFromAssets(assets, fragmentShaderFileName))
        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertexShader)
        GLES30.glAttachShader(programId, fragmentShader)
        GLES30.glLinkProgram(programId)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val infoLog = GLES30.glGetProgramInfoLog(programId)
            throw RuntimeException("Could not link program: $infoLog")
        }
    }

    fun use() {
        GLES30.glUseProgram(programId)
    }

    fun getProgramId(): Int {
        return programId
    }

    override fun close() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val infoLog = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $infoLog")
        }
        return shader
    }

    private fun readTextFileFromAssets(assets: AssetManager, fileName: String): String {
        return InputStreamReader(assets.open(fileName)).readText()
    }
}
