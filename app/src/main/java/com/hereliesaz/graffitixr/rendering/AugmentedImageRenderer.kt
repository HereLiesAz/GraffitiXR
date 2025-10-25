package com.hereliesaz.graffitixr.rendering

import android.opengl.GLES20
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class AugmentedImageRenderer {
    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpMatrixHandle = 0

    private val vertices = floatArrayOf(
        -0.5f, -0.5f, 0.0f,
        0.5f, -0.5f, 0.0f,
        0.5f, 0.5f, 0.0f,
        -0.5f, 0.5f, 0.0f
    )
    private val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
    private val textureCoordinates = floatArrayOf(
        0.0f, 1.0f, // top left
        1.0f, 1.0f, // top right
        1.0f, 0.0f, // bottom right
        0.0f, 0.0f  // bottom left
    )


    private var verticesBuffer: FloatBuffer? = null
    private var indicesBuffer: java.nio.ShortBuffer? = null
    private var textureCoordinatesBuffer: FloatBuffer? = null


    private val color = floatArrayOf(0.0f, 1.0f, 0.0f, 0.5f)
    private var textureHandle = 0
    private var textureCoordinatesHandle = 0

    fun createOnGlThread() {
        val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also { shader ->
            GLES20.glShaderSource(shader, VERTEX_SHADER)
            GLES20.glCompileShader(shader)
        }
        val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also { shader ->
            GLES20.glShaderSource(shader, FRAGMENT_SHADER)
            GLES20.glCompileShader(shader)
        }

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        textureCoordinatesHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "u_Texture")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")

        val bbVertices = ByteBuffer.allocateDirect(vertices.size * 4)
        bbVertices.order(ByteOrder.nativeOrder())
        verticesBuffer = bbVertices.asFloatBuffer()
        verticesBuffer?.put(vertices)
        verticesBuffer?.position(0)

        val bbIndices = ByteBuffer.allocateDirect(indices.size * 2)
        bbIndices.order(ByteOrder.nativeOrder())
        indicesBuffer = bbIndices.asShortBuffer()
        indicesBuffer?.put(indices)
        indicesBuffer?.position(0)

        val bbTexCoords = ByteBuffer.allocateDirect(textureCoordinates.size * 4)
        bbTexCoords.order(ByteOrder.nativeOrder())
        textureCoordinatesBuffer = bbTexCoords.asFloatBuffer()
        textureCoordinatesBuffer?.put(textureCoordinates)
        textureCoordinatesBuffer?.position(0)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, pose: Pose, width: Float, height: Float, textureId: Int) {
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, verticesBuffer)

        GLES20.glEnableVertexAttribArray(textureCoordinatesHandle)
        GLES20.glVertexAttribPointer(textureCoordinatesHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoordinatesBuffer)

        val modelMatrix = FloatArray(16)
        pose.toMatrix(modelMatrix, 0)

        val scaleMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(scaleMatrix, 0)
        android.opengl.Matrix.scaleM(scaleMatrix, 0, width, 1.0f, height)

        val modelViewMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(modelViewMatrix, 0, modelMatrix, 0, scaleMatrix, 0)

        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, indicesBuffer)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordinatesHandle)
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            uniform mat4 u_MvpMatrix;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = u_MvpMatrix * a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}
