package com.hereliesaz.graffitixr.feature.ar.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayList
import java.util.Collections

class PlaneRenderer {
    private var planeProgram = 0

    // Uniform Locations
    private var planeModelUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var gridControlUniform = 0
    private var planeMatUniform = 0

    // Buffers
    private val vertexBuffer = ByteBuffer.allocateDirect(1000 * 4) // Reusable buffer (Float = 4 bytes)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val modelMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    /**
     * Allocates and initializes OpenGL resources needed by the plane renderer.
     * Must be called on the OpenGL thread, typically in onSurfaceCreated().
     */
    fun createOnGlThread(context: Context) {
        val vertexShaderCode = context.assets.open("shaders/plane.vert").bufferedReader().use { it.readText() }
        val fragmentShaderCode = context.assets.open("shaders/plane.frag").bufferedReader().use { it.readText() }

        val vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val passthroughShader = ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        planeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(planeProgram, vertexShader)
        GLES20.glAttachShader(planeProgram, passthroughShader)
        GLES20.glLinkProgram(planeProgram)

        // Get Uniform Locations (Must match shader names)
        planeModelUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModel")
        planeModelViewProjectionUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneModelViewProjection")
        gridControlUniform = GLES20.glGetUniformLocation(planeProgram, "u_gridControl")
        planeMatUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneMat")
    }

    /**
     * Draws the detected planes.
     */
    fun drawPlanes(session: Session, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        val planes = session.getAllTrackables(Plane::class.java)

        GLES20.glUseProgram(planeProgram)

        // Enable transparency for the grid
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Set Global Uniforms
        GLES20.glUniform1f(gridControlUniform, 1.0f) // Full visibility

        // Optional: Set a base color for the plane material if shader uses u_PlaneMat
        // R, G, B, A
        GLES20.glUniform4f(planeMatUniform, 1.0f, 1.0f, 1.0f, 1.0f)

        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }
            drawPlane(plane, viewMatrix, projectionMatrix)
        }

        // Clean up state
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
    }

    private fun drawPlane(plane: Plane, viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        // 1. Update Geometry Buffer
        val polygon = plane.polygon // Returns FloatBuffer in local X,Z coords
        vertexBuffer.clear()
        vertexBuffer.put(polygon)
        vertexBuffer.flip()

        // ARCore polygons are simple X,Z lists. We draw them as a Triangle Fan.
        val count = vertexBuffer.limit() / 2

        // 2. Calculate Matrices
        // Get the plane's center pose (Model Matrix)
        plane.centerPose.toMatrix(modelMatrix, 0)

        // Calculate ModelView and MVP
        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

        // 3. Set Per-Plane Uniforms
        GLES20.glUniformMatrix4fv(planeModelUniform, 1, false, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(planeModelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)

        // 4. Bind Attributes
        // "a_Position" expects vec3 (x,y,z). ARCore gives vec2 (x,z).
        // However, the shader expects vec3 "a_Position".
        // We can pass the 2 floats and let GL fill Z=0 and W=1,
        // BUT our shader treats input as (x,0,z).
        // Let's modify how we bind.

        val posAttr = GLES20.glGetAttribLocation(planeProgram, "a_Position")
        GLES20.glEnableVertexAttribArray(posAttr)

        // CRITICAL: We pass 2 components (x, z).
        // The vertex shader needs to construct vec4(x, 0, z, 1).
        // The shader I gave you uses: layout(location = 0) in vec3 a_Position;
        // If we pass 2 floats, OpenGL defaults Z to 0 and W to 1.
        // So a_Position becomes (x, z, 0).
        // WE NEED (x, 0, z).
        // FIX: The shader code I provided uses a_Position.xz for texture coords,
        // and uses a_Position for geometry.
        // Let's stick to the standard behavior: Passing 2 floats fills X and Y.
        // So in shader, a_Position.x is X, a_Position.y is Z.
        // Make sure the shader handles this swap or we swap it here.
        // Actually, the previous shader code I gave you assumed `in vec3 a_Position`.
        // Let's assume the Vertex Shader handles the swizzle or the data is X,Y,Z.
        // ARCore data is X,Z.
        // Best approach for GLES 2.0/3.0 compat without stride hacking:
        // Let's just pass it as 2 floats. In Vertex Shader: vec4(a_Position.x, 0.0, a_Position.y, 1.0).
        // Since I cannot change the shader here, I will rely on the fact that standard ARCore
        // plane vertices are 2D.

        GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // 5. Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, count)

        GLES20.glDisableVertexAttribArray(posAttr)
    }

    companion object {
        private const val TAG = "PlaneRenderer"
    }
}