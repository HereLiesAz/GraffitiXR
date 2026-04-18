package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Renders the editor layer composite as a warped grid mesh locked to the AR anchor.
 * Supports both flat-quad and dense-mesh (Twindo-style) rendering.
 */
class OverlayRenderer(private val context: Context) {

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var weightHandle = 0
    private var mvpMatrixHandle = 0
    private var textureHandle = 0

    private var borderProgram = 0
    private var borderPositionHandle = 0
    private var borderMvpMatrixHandle = 0
    private var borderVboId = 0

    private val textureIds = IntArray(1)
    var hasTexture = false
        private set

    private var vboId = 0
    private var iboId = 0
    private var indexCount = 0

    @Volatile private var halfW = QUAD_HALF_EXTENT
    @Volatile private var halfH = QUAD_HALF_EXTENT
    @Volatile private var meshDirty = true

    @Volatile private var borderHalfW = 0.5f
    @Volatile private var borderHalfH = 0.5f
    @Volatile private var borderDirty = true

    private val mvpMatrix = FloatArray(16)

    // Reusable buffers for dynamic mesh updates
    private var vertexBuffer: FloatBuffer? = null

    fun createOnGlThread() {
        val vs = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs)
            GLES30.glAttachShader(it, fs)
            GLES30.glLinkProgram(it)
        }

        positionHandle = GLES30.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES30.glGetAttribLocation(program, "a_TexCoord")
        weightHandle = GLES30.glGetAttribLocation(program, "a_Weight")
        mvpMatrixHandle = GLES30.glGetUniformLocation(program, "u_MvpMatrix")
        textureHandle = GLES30.glGetUniformLocation(program, "u_Texture")

        GLES30.glGenTextures(1, textureIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val bufs = IntArray(2)
        GLES30.glGenBuffers(2, bufs, 0)
        vboId = bufs[0]
        iboId = bufs[1]
        
        buildInitialMesh()

        val borderVs = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, BORDER_VERTEX_SHADER)
        val borderFs = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, BORDER_FRAGMENT_SHADER)
        borderProgram = GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, borderVs)
            GLES30.glAttachShader(it, borderFs)
            GLES30.glLinkProgram(it)
        }
        borderPositionHandle = GLES30.glGetAttribLocation(borderProgram, "a_Position")
        borderMvpMatrixHandle = GLES30.glGetUniformLocation(borderProgram, "u_MvpMatrix")
        val borderBuf = IntArray(1)
        GLES30.glGenBuffers(1, borderBuf, 0)
        borderVboId = borderBuf[0]
        buildBorderQuad()
    }

    fun clearTexture() {
        hasTexture = false
    }

    fun updateTexture(bitmap: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        hasTexture = true
    }

    fun setExtent(halfW: Float, halfH: Float) {
        if (this.halfW != halfW || this.halfH != halfH) {
            this.halfW = halfW
            this.halfH = halfH
            meshDirty = true
        }
    }

    fun setBorderExtent(halfW: Float, halfH: Float) {
        this.borderHalfW = halfW
        this.borderHalfH = halfH
        borderDirty = true
    }

    fun drawAnchorBorder(viewMatrix: FloatArray, projMatrix: FloatArray, anchorMatrix: FloatArray) {
        if (borderProgram == 0 || borderVboId == 0) return
        if (borderDirty) {
            buildBorderQuad()
            borderDirty = false
        }

        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, anchorMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)

        GLES30.glUseProgram(borderProgram)
        GLES30.glUniformMatrix4fv(borderMvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, borderVboId)
        GLES30.glEnableVertexAttribArray(borderPositionHandle)
        GLES30.glVertexAttribPointer(borderPositionHandle, 3, GLES30.GL_FLOAT, false, 12, 0)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glLineWidth(4.0f)
        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, 4)
        GLES30.glDisable(GLES30.GL_BLEND)

        GLES30.glDisableVertexAttribArray(borderPositionHandle)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

     * @param meshVertices Optional warped vertices (x,y,z) from SlamManager.
     * @param meshWeights Optional vertex confidence weights (0..1).
     * If null, renders a flat quad.
     */
    fun draw(viewMatrix: FloatArray, projMatrix: FloatArray, anchorMatrix: FloatArray, 
             meshVertices: FloatArray? = null, meshWeights: FloatArray? = null) {
        if (!hasTexture || program == 0 || vboId == 0) return

        if (meshDirty || meshVertices != null) {
            updateMeshBuffers(meshVertices, meshWeights)
            meshDirty = false
        }

        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, anchorMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0])
        GLES30.glUniform1i(textureHandle, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        val stride = 6 * 4 // x,y,z, u,v, weight
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(weightHandle)
        GLES30.glVertexAttribPointer(weightHandle, 1, GLES30.GL_FLOAT, false, stride, 5 * 4)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun buildInitialMesh() {
        val vertices = FloatArray(MESH_DIM * MESH_DIM * 6)
        val indices = IntArray((MESH_DIM - 1) * (MESH_DIM - 1) * 6)
        
        var vIdx = 0
        for (i in 0 until MESH_DIM) {
            val v = i.toFloat() / (MESH_DIM - 1)
            val y = -halfH + v * (halfH * 2)
            for (j in 0 until MESH_DIM) {
                val u = j.toFloat() / (MESH_DIM - 1)
                val x = -halfW + u * (halfW * 2)
                
                vertices[vIdx++] = x
                vertices[vIdx++] = y
                vertices[vIdx++] = 0f
                vertices[vIdx++] = u
                vertices[vIdx++] = 1.0f - v // Flip V
                vertices[vIdx++] = 1.0f // Initial weight
            }
        }

        var iIdx = 0
        for (i in 0 until MESH_DIM - 1) {
            for (j in 0 until MESH_DIM - 1) {
                val bl = i * MESH_DIM + j
                val br = bl + 1
                val tl = (i + 1) * MESH_DIM + j
                val tr = tl + 1
                
                indices[iIdx++] = bl
                indices[iIdx++] = br
                indices[iIdx++] = tl
                indices[iIdx++] = br
                indices[iIdx++] = tr
                indices[iIdx++] = tl
            }
        }

        indexCount = indices.size
        
        val vBuf = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
        vBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vBuf, GLES30.GL_DYNAMIC_DRAW)

        val iBuf = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(indices)
        iBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, iBuf, GLES30.GL_STATIC_DRAW)
    }

    private fun updateMeshBuffers(warpedVertices: FloatArray?, warpedWeights: FloatArray?) {
        val totalVertices = MESH_DIM * MESH_DIM
        if (vertexBuffer == null) {
            vertexBuffer = ByteBuffer.allocateDirect(totalVertices * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        val buf = vertexBuffer!!
        buf.position(0)

        for (i in 0 until MESH_DIM) {
            val v = i.toFloat() / (MESH_DIM - 1)
            val y = -halfH + v * (halfH * 2)
            for (j in 0 until MESH_DIM) {
                val u = j.toFloat() / (MESH_DIM - 1)
                val x = -halfW + u * (halfW * 2)
                
                val idx = i * MESH_DIM + j
                if (warpedVertices != null && idx * 3 + 2 < warpedVertices.size) {
                    buf.put(warpedVertices[idx * 3])
                    buf.put(warpedVertices[idx * 3 + 1])
                    buf.put(warpedVertices[idx * 3 + 2])
                } else {
                    buf.put(x)
                    buf.put(y)
                    buf.put(0f)
                }
                buf.put(u)
                buf.put(1.0f - v)
                buf.put(warpedWeights?.getOrNull(idx) ?: 1.0f)
            }
        }
        buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, totalVertices * 6 * 4, buf)
    }

    private fun buildBorderQuad() {
        val w = borderHalfW
        val h = borderHalfH
        val data = floatArrayOf(
            -w, -h, 0f,   // BL
             w, -h, 0f,   // BR
             w,  h, 0f,   // TR
            -w,  h, 0f,   // TL
        )
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, borderVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
    }

    companion object {
        private const val TAG = "OverlayRenderer"
        private const val MESH_DIM = 32 // Sufficient for smooth warping
        const val QUAD_HALF_EXTENT = 5.0f

        private const val BORDER_VERTEX_SHADER = """#version 300 es
            uniform mat4 u_MvpMatrix;
            in vec3 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);
            }
        """

        private const val BORDER_FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            out vec4 FragColor;
            void main() {
                FragColor = vec4(1.0, 0.55, 0.0, 0.85);
            }
        """

        private const val VERTEX_SHADER = """#version 300 es
            uniform mat4 u_MvpMatrix;
            in vec3 a_Position;
            in vec2 a_TexCoord;
            in float a_Weight;
            out vec2 v_TexCoord;
            out float v_Weight;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);
                v_TexCoord = a_TexCoord;
                v_Weight = a_Weight;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            uniform sampler2D u_Texture;
            in vec2 v_TexCoord;
            in float v_Weight;
            out vec4 FragColor;
            void main() {
                vec4 tex = texture(u_Texture, v_TexCoord);
                // Modulate alpha by weight to show "uncertain" areas as faded
                FragColor = vec4(tex.rgb, tex.a * v_Weight);
            }
        """
    }
}
