package com.hereliesaz.graffitixr.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import com.google.ar.core.PointCloud
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders an egocentric, isometric "Tactical View" of the AR world.
 * Camera is locked to the user's orientation but offset 45 degrees up and to the right.
 */
class MiniMapRenderer {

    private var programId: Int = 0
    private var positionAttribute: Int = 0
    private var colorUniform: Int = 0
    private var modelViewProjectionUniform: Int = 0
    private var pointSizeUniform: Int = 0

    private var pointVboId = 0
    private var frustumVboId = 0
    private var numFrustumVertices = 0

    // Matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val deviceModelMatrix = FloatArray(16)

    // Math Caches
    private val cameraOffset = FloatArray(3)
    private val rotatedOffset = FloatArray(3)

    fun createOnGlThread(context: Context) {
        // FIXED: Correct argument order for ShaderUtil.loadGLShader(tag, code, type)
        val vertexShader = ShaderUtil.loadGLShader(TAG, VERTEX_SHADER_CODE, GLES20.GL_VERTEX_SHADER)
        val fragmentShader = ShaderUtil.loadGLShader(TAG, FRAGMENT_SHADER_CODE, GLES20.GL_FRAGMENT_SHADER)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
        GLES20.glUseProgram(programId)

        positionAttribute = GLES20.glGetAttribLocation(programId, "a_Position")
        colorUniform = GLES20.glGetUniformLocation(programId, "u_Color")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(programId, "u_ModelViewProjection")
        pointSizeUniform = GLES20.glGetUniformLocation(programId, "u_PointSize")

        // Generate VBOs
        val buffers = IntArray(2)
        GLES20.glGenBuffers(2, buffers, 0)
        pointVboId = buffers[0]
        frustumVboId = buffers[1]

        // Setup Frustum Geometry (Device Indicator)
        val scale = 0.15f
        val w = 0.15f * scale
        val h = 0.2f * scale
        val d = 0.5f * scale

        val frustumCoords = floatArrayOf(
            0f, 0f, 0f, -w, h, -d,
            0f, 0f, 0f, w, h, -d,
            0f, 0f, 0f, w, -h, -d,
            0f, 0f, 0f, -w, -h, -d,
            -w, h, -d, w, h, -d,
            w, h, -d, w, -h, -d,
            w, -h, -d, -w, -h, -d,
            -w, -h, -d, -w, h, -d
        )
        numFrustumVertices = frustumCoords.size / 3

        val bb = ByteBuffer.allocateDirect(frustumCoords.size * 4)
        bb.order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(frustumCoords)
        fb.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, frustumVboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, frustumCoords.size * 4, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(pointCloud: PointCloud, devicePose: Pose, viewportWidth: Int, viewportHeight: Int, navRailWidthPx: Int) {
        if (pointCloud.points == null || pointCloud.points.remaining() < 3) return

        // 1. Layout: Top Area, Right of NavRail
        val mapHeight = (viewportHeight * 0.30f).toInt() // 30% Height
        val mapWidth = viewportWidth - navRailWidthPx
        val mapX = navRailWidthPx
        val mapY = viewportHeight - mapHeight

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(mapX, mapY, mapWidth, mapHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.6f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        GLES20.glViewport(mapX, mapY, mapWidth, mapHeight)
        GLES20.glUseProgram(programId)

        // 2. Camera Logic (Drone View)
        // Offset: Right 2.0m, Up 2.5m, Back 2.0m
        cameraOffset[0] = 2.0f
        cameraOffset[1] = 2.5f
        cameraOffset[2] = 2.0f

        devicePose.rotateVector(cameraOffset, 0, rotatedOffset, 0)

        val camX = devicePose.tx() + rotatedOffset[0]
        val camY = devicePose.ty() + rotatedOffset[1]
        val camZ = devicePose.tz() + rotatedOffset[2]

        Matrix.setLookAtM(viewMatrix, 0,
            camX, camY, camZ,
            devicePose.tx(), devicePose.ty(), devicePose.tz(),
            0f, 1f, 0f // World Up
        )

        val ratio = mapWidth.toFloat() / mapHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 100f)

        // 3. Render Points
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pointVboId)
        val numPoints = pointCloud.points.remaining() / 4
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, numPoints * 16, pointCloud.points, GLES20.GL_DYNAMIC_DRAW)

        GLES20.glVertexAttribPointer(positionAttribute, 4, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute)

        GLES20.glUniform4f(colorUniform, 0.0f, 1.0f, 0.9f, 1.0f) // Cyber Cyan
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointSizeUniform, 6.0f)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

        // 4. Render Frustum
        devicePose.toMatrix(deviceModelMatrix, 0)
        Matrix.multiplyMM(modelMatrix, 0, viewMatrix, 0, deviceModelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, modelMatrix, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, frustumVboId)
        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 12, 0)

        GLES20.glUniform4f(colorUniform, 1.0f, 0.2f, 0.6f, 1.0f) // Neon Pink
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)

        GLES20.glLineWidth(4.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, numFrustumVertices)

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    companion object {
        private const val TAG = "MiniMapRenderer"
        private const val VERTEX_SHADER_CODE = """
            uniform mat4 u_ModelViewProjection;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
            }
        """
        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                vec2 coord = gl_PointCoord - vec2(0.5);
                if(length(coord) > 0.5) discard;
                gl_FragColor = u_Color;
            }
        """
    }
}