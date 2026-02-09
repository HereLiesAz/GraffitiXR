package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

object ShaderUtil {
    fun loadGLShader(tag: String, context: Context, type: Int, filename: String): Int {
        val code = try {
            readShaderFileFromAssets(context, filename)
        } catch (e: IOException) {
            Log.e(tag, "Error reading shader file: $filename", e)
            return 0
        }

        var shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)

        // Get the compilation status.
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)

        // If the compilation failed, delete the shader.
        if (compileStatus[0] == 0) {
            Log.e(tag, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        if (shader == 0) {
            Log.e(tag, "Error creating shader: $filename")
            // throw RuntimeException("Error creating shader.") // Changed behavior: Log instead of throw to avoid crash loop
        }
        return shader
    }

    // Convenience overload to match existing calls if they use 3 args
    fun loadGLShader(filename: String, type: Int, context: Context): Int {
        return loadGLShader("ShaderUtil", context, type, filename)
    }

    @Throws(IOException::class)
    private fun readShaderFileFromAssets(context: Context, filename: String): String {
        context.assets.open(filename).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                return sb.toString()
            }
        }
    }
}
