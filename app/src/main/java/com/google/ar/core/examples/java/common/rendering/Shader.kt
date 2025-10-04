package com.google.ar.core.examples.java.common.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import java.io.Closeable
import java.nio.FloatBuffer
import java.util.HashMap

class Shader(private val vertexShaderFileName: String, private val fragmentShaderFileName: String) : Closeable {
    private var program = 0
    private val uniforms: MutableMap<String, Uniform> = HashMap()
    private val attributes: MutableMap<String, Attribute> = HashMap()
    private var depthTest = true
    private var depthWrite = true
    private var blending = false

    fun create() {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderFileName)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderFileName)
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val infoLog = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            program = 0
            throw RuntimeException("Could not link program: $infoLog")
        }

        // Query and store uniforms.
        val numUniforms = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_ACTIVE_UNIFORMS, numUniforms, 0)
        for (i in 0 until numUniforms[0]) {
            val type = IntArray(1)
            val size = IntArray(1)
            val nameBytes = ByteArray(256)
            val length = IntArray(1)
            GLES30.glGetActiveUniform(program, i, nameBytes.size, length, 0, size, 0, type, 0, nameBytes, 0)
            val name = String(nameBytes, 0, length[0])
            val location = GLES30.glGetUniformLocation(program, name)
            val uniform = Uniform(location, type[0])
            if (uniform.type == GLES30.GL_SAMPLER_2D || uniform.type == GLES11Ext.GL_SAMPLER_EXTERNAL_OES) {
                uniform.textureUnit = uniforms.size
            }
            uniforms[name] = uniform
        }
    }

    override fun close() {
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }

    fun setDepthTest(depthTest: Boolean): Shader {
        this.depthTest = depthTest
        return this
    }

    fun setDepthWrite(depthWrite: Boolean): Shader {
        this.depthWrite = depthWrite
        return this
    }

    fun setBlending(blending: Boolean): Shader {
        this.blending = blending
        return this
    }

    fun setTexture(name: String, textureId: Int) {
        val uniform = getUniform(name)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + uniform.textureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(uniform.location, uniform.textureUnit)
    }

    fun setTexture(name: String, texture: Texture) {
        val uniform = getUniform(name)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + uniform.textureUnit)
        GLES30.glBindTexture(texture.target, texture.textureId[0])
        GLES30.glUniform1i(uniform.location, uniform.textureUnit)
    }

    fun setFloat(name: String, v: Float) {
        GLES30.glUniform1f(getUniform(name).location, v)
    }

    fun setVec2(name: String, v: FloatArray) {
        require(v.size == 2) { "The array must have 2 elements." }
        GLES30.glUniform2fv(getUniform(name).location, 1, v, 0)
    }

    fun setVec3(name: String, v: FloatArray) {
        require(v.size == 3) { "The array must have 3 elements." }
        GLES30.glUniform3fv(getUniform(name).location, 1, v, 0)
    }

    fun setVec4(name: String, v: FloatArray) {
        require(v.size == 4) { "The array must have 4 elements." }
        GLES30.glUniform4fv(getUniform(name).location, 1, v, 0)
    }

    fun setMat2(name: String, m: FloatArray) {
        require(m.size == 4) { "The array must have 4 elements." }
        GLES30.glUniformMatrix2fv(getUniform(name).location, 1, false, m, 0)
    }

    fun setMat3(name: String, m: FloatArray) {
        require(m.size == 9) { "The array must have 9 elements." }
        GLES30.glUniformMatrix3fv(getUniform(name).location, 1, false, m, 0)
    }

    fun setMat4(name: String, m: FloatArray) {
        require(m.size == 16) { "The array must have 16 elements." }
        GLES30.glUniformMatrix4fv(getUniform(name).location, 1, false, m, 0)
    }

    fun setVertexAttrib(name: String, size: Int, buffer: FloatBuffer?) {
        val attribute = getAttribute(name)
        GLES30.glVertexAttribPointer(attribute.location, size, GLES30.GL_FLOAT, false, 0, buffer)
        GLES30.glEnableVertexAttribArray(attribute.location)
    }

    fun draw() {
        if (depthTest) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        } else {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glDepthMask(depthWrite)
        if (blending) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glUseProgram(program)
        for (uniform in uniforms.values) {
            if (uniform.textureUnit != -1) {
                GLES30.glUniform1i(uniform.location, uniform.textureUnit)
            }
        }
        for (attribute in attributes.values) {
            GLES30.glEnableVertexAttribArray(attribute.location)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        for (attribute in attributes.values) {
            GLES30.glDisableVertexAttribArray(attribute.location)
        }
    }

    private fun getUniform(name: String): Uniform {
        return uniforms[name] ?: throw IllegalArgumentException("Uniform not found: $name")
    }

    private fun getAttribute(name: String): Attribute {
        if (attributes.containsKey(name)) {
            return attributes[name]!!
        }
        val location = GLES30.glGetAttribLocation(program, name)
        if (location == -1) {
            throw IllegalArgumentException("Attribute not found: $name")
        }
        val attribute = Attribute(location)
        attributes[name] = attribute
        return attribute
    }

    private class Uniform(val location: Int, val type: Int) {
        var textureUnit = -1
    }

    private class Attribute(val location: Int)
    companion object {
        private val TAG = Shader::class.java.simpleName

        fun loadShader(type: Int, filename: String?): Int {
            val shader = GLES30.glCreateShader(type)
            val shaderCode =
                Shader::class.java.classLoader!!.getResourceAsStream(filename).bufferedReader().use { it.readText() }
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

        fun checkGLError(label: String) {
            var error: Int
            while (GLES30.glGetError().also { error = it } != GLES30.GL_NO_ERROR) {
                Log.e(TAG, "$label: glError $error")
                throw RuntimeException("$label: glError $error")
            }
        }
    }
}