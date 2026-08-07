package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import com.hereliesaz.graffitixr.common.util.GlReleasable
import com.hereliesaz.graffitixr.design.rendering.ShaderUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap

class PointCloudRenderer : GlReleasable {
    private val TAG = "PointCloudRenderer"

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var mvpMatrixHandle: Int = 0
    private var pointSizeHandle: Int = 0

    private val maxPoints = 50000
    @Volatile var accumulatedPointCount = 0
        private set
    private var vboId = 0

    // Accumulated points, stored ANCHOR-LOCAL as (x, y, z, confidence) — see [update].
    private val localBuffer = FloatArray(maxPoints * 4)
    private val pointIdMap = HashMap<Int, Int>()

    // Scratch for the world->anchor transform in [update]; GL-thread only, so one instance is fine.
    private val localScratch = FloatArray(4)
    private val worldScratch = FloatArray(4)
    private val mvpMatrix = FloatArray(16)
    private val viewProj = FloatArray(16)

    // Reusable GL upload buffers (GL thread only). [update] runs every frame while scanning, so
    // allocating a fresh direct buffer each call churned the GC and caused scan-time jank; allocate
    // the max-size buffer once and re-fill the used prefix instead.
    private val uploadByteBuffer = ByteBuffer.allocateDirect(maxPoints * 4 * 4).order(ByteOrder.nativeOrder())
    private val uploadFloatBuffer = uploadByteBuffer.asFloatBuffer()

    fun createOnGlThread(context: Context) {
        val vertexShaderCode = """
            uniform mat4 u_MvpMatrix;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(0.0, 1.0, 1.0, 1.0);
            }
        """.trimIndent()

        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        pointSizeHandle = GLES20.glGetUniformLocation(program, "u_PointSize")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, maxPoints * 4 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Fold this frame's ARCore feature points into the accumulated cloud.
     *
     * ## Why the points are stored anchor-local, and why the position is always taken
     *
     * ARCore's point cloud reports positions in the CURRENT frame's world estimate, and that
     * estimate is not fixed: ARCore continuously refines each feature's position as it gathers
     * observations, and periodically corrects the whole world frame at once (loop closure). A point
     * captured a minute ago is expressed in a frame that no longer exists.
     *
     * This class used to ignore both facts. It wrote a point's position once and then only ever
     * overwrote it when a later observation arrived with strictly HIGHER confidence:
     *
     *     if (conf > oldConf) { ...write x, y, z... }
     *
     * Confidence is not monotonic, so in practice the position was frozen at roughly first sighting
     * and every subsequent refinement was thrown away — and world corrections were thrown away too,
     * because nothing re-read the old points at all. The accumulated cloud therefore drifted away
     * from the camera image, visibly and permanently, over the life of a session. That is the defect
     * this rewrite exists to fix, so the confidence comparison is gone: the newest observation of a
     * point is always the best one, because it is the one expressed in the newest world estimate.
     *
     * Refreshing the points currently in view is necessary but not sufficient — a point ARCore is
     * not looking at right now still has to move when the world frame is corrected. That is what an
     * [com.google.ar.core.Anchor] is for: ARCore updates an anchor's pose to keep it attached to the
     * real world across exactly those corrections. So the points are stored relative to the caller's
     * anchor and drawn through its live pose ([draw]), which makes the whole accumulated cloud
     * inherit every correction ARCore applies, whether or not a given point is in frame.
     *
     * @param worldToAnchor the inverse of the cloud anchor's pose, column-major 4x4. Points are
     *   transformed by it before being stored, so [localBuffer] holds anchor-local coordinates.
     */
    fun update(pointCloud: PointCloud, worldToAnchor: FloatArray) {
        val points = pointCloud.points ?: return
        val ids = pointCloud.ids ?: return

        val numPoints = points.remaining() / 4
        var hasUpdates = false

        for (i in 0 until numPoints) {
            val id = ids.get(i)
            worldScratch[0] = points.get(i * 4)
            worldScratch[1] = points.get(i * 4 + 1)
            worldScratch[2] = points.get(i * 4 + 2)
            worldScratch[3] = 1f
            val conf = points.get(i * 4 + 3)
            Matrix.multiplyMV(localScratch, 0, worldToAnchor, 0, worldScratch, 0)

            val index = pointIdMap[id] ?: run {
                if (accumulatedPointCount >= maxPoints) return@run null
                val fresh = accumulatedPointCount
                pointIdMap[id] = fresh
                accumulatedPointCount++
                fresh
            } ?: continue

            val offset = index * 4
            localBuffer[offset] = localScratch[0]
            localBuffer[offset + 1] = localScratch[1]
            localBuffer[offset + 2] = localScratch[2]
            localBuffer[offset + 3] = conf
            hasUpdates = true
        }

        if (hasUpdates) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)

            val byteCount = accumulatedPointCount * 4 * 4
            uploadFloatBuffer.clear()
            uploadFloatBuffer.put(localBuffer, 0, accumulatedPointCount * 4)
            uploadByteBuffer.position(0)

            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, byteCount, uploadByteBuffer)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    /**
     * Draw the accumulated cloud through the live pose of the anchor its points are stored against.
     *
     * [anchorModel] is the cloud anchor's current pose as a column-major 4x4 (anchor -> world). It
     * must be re-read from the anchor every frame: that is the whole mechanism by which ARCore's
     * world corrections reach points it is not currently observing.
     */
    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, anchorModel: FloatArray) {
        if (accumulatedPointCount == 0) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        Matrix.multiplyMM(viewProj, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProj, 0, anchorModel, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointSizeHandle, 15.0f)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, accumulatedPointCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun clear() {
        accumulatedPointCount = 0
        pointIdMap.clear()
    }

    /**
     * Deletes the shader program and VBO this renderer owns and resets accumulation.
     * Idempotent; must run on the GL thread.
     */
    override fun release() {
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        if (vboId != 0) { GLES20.glDeleteBuffers(1, intArrayOf(vboId), 0); vboId = 0 }
        clear()
    }
}
