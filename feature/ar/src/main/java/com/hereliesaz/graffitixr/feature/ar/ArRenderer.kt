package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.PointCloud
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.feature.ar.rendering.ShaderUtil
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class SlamManager {
    val mappingQuality = MutableStateFlow(0f)
    fun clearMap() {}
    fun saveWorld(path: String): Boolean = true
}

class ArRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var programId: Int = 0
    private var positionAttribute: Int = 0
    private var modelViewProjectionUniform: Int = 0
    private var colorUniform: Int = 0

    private var vboId: Int = 0
    private var numPoints: Int = 0
    
    private var viewportWidth = 0
    private var viewportHeight = 0

    // Stride for filtered rendering (Show 1 in every 20 points)
    private val POINT_STRIDE = 20

    var showPointCloud = true
        private set

    var session: Session? = null
        private set

    val view: View get() = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        setRenderer(this@ArRenderer)
    }

    val slamManager = SlamManager()

    fun setSession(session: Session) {
        this.session = session
    }

    fun setShowPointCloud(show: Boolean) {
        this.showPointCloud = show
    }

    fun setFlashlight(enabled: Boolean) {
        // Flashlight implementation if needed
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // Rendering logic would go here
    }

    fun onResume(owner: LifecycleOwner) {}
    fun onPause(owner: LifecycleOwner) {}

    fun createOnGlThread() {
        val vertexShader = ShaderUtil.loadGLShader(
            "ArRenderer",
            context,
            GLES20.GL_VERTEX_SHADER,
            VERTEX_SHADER
        )
        val fragmentShader = ShaderUtil.loadGLShader(
            "ArRenderer",
            context,
            GLES20.GL_FRAGMENT_SHADER,
            FRAGMENT_SHADER
        )

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        positionAttribute = GLES20.glGetAttribLocation(programId, "a_Position")
        modelViewProjectionUniform = GLES20.glGetUniformLocation(programId, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(programId, "u_Color")

        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)
        vboId = buffers[0]
    }

    fun updatePointCloud(pointCloud: PointCloud) {
        val buffer = pointCloud.points
        val pointsRemaining = buffer.remaining() / 4 

        val filteredPointCount = pointsRemaining / POINT_STRIDE
        if (filteredPointCount == 0) {
            numPoints = 0
            return
        }

        val filteredBuffer = ByteBuffer.allocateDirect(filteredPointCount * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for (i in 0 until pointsRemaining step POINT_STRIDE) {
            val offset = i * 4
            filteredBuffer.put(buffer.get(offset))     // x
            filteredBuffer.put(buffer.get(offset + 1)) // y
            filteredBuffer.put(buffer.get(offset + 2)) // z
            filteredBuffer.put(buffer.get(offset + 3)) // confidence
        }

        filteredBuffer.position(0)
        numPoints = filteredPointCount

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            numPoints * 16, 
            filteredBuffer,
            GLES20.GL_DYNAMIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray) {
        if (!showPointCloud || numPoints == 0) return

        GLES20.glUseProgram(programId)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 4, GLES20.GL_FLOAT, false, 16, 0)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun cleanup() {
        session?.close()
        session = null
    }

    fun setupAugmentedImageDatabase(bitmap: Bitmap?, name: String = "") {
    }

    fun captureFrame(onBitmapCaptured: (Bitmap) -> Unit) {
        val w = viewportWidth
        val h = viewportHeight
        if (w <= 0 || h <= 0) return

        val pixelBuffer = IntArray(w * h)
        val bitmapBuffer = IntBuffer.wrap(pixelBuffer)
        bitmapBuffer.position(0)

        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bitmapBuffer)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(IntBuffer.wrap(pixelBuffer))
        
        // OpenGL uses bottom-left origin, Bitmap uses top-left. Flip it.
        val matrix = android.graphics.Matrix()
        matrix.postScale(1f, -1f)
        val flippedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
        
        onBitmapCaptured(flippedBitmap)
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
                gl_PointSize = 5.0;
                v_Color = vec4(1.0, 1.0, 1.0, a_Position.w);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 v_Color;
            void main() {
                gl_FragColor = v_Color;
            }
        """
    }
}
