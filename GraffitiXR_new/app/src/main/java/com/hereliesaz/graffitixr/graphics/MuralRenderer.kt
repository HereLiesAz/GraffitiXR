package com.hereliesaz.graffitixr.graphics

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.hereliesaz.graffitixr.UiState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A custom [GLSurfaceView.Renderer] for the mural overlay in the static image editor.
 *
 * This class implements the low-level OpenGL ES 2.0 rendering pipeline to draw a bitmap
 * onto a quad, which is then warped and styled according to user-defined parameters.
 *
 * ### Graphics Pipeline Overview:
 * 1.  **Initialization (`onSurfaceCreated`)**:
 *     -   Sets the clear color to transparent.
 *     -   Initializes buffers for vertex positions (`vertexBuffer`), texture coordinates (`uvBuffer`),
 *         and draw order indices (`indexBuffer`).
 *     -   Loads, compiles, and links the vertex and fragment shaders from the `assets/shaders` directory.
 *     -   Generates a single texture ID and configures its filtering and wrapping parameters.
 *
 * 2.  **Projection Setup (`onSurfaceChanged`)**:
 *     -   Sets the OpenGL viewport to match the surface dimensions.
 *     -   Creates an orthographic projection matrix (`mvpMatrix`) to map the normalized device
 *         coordinates (-1 to 1) to the screen space.
 *
 * 3.  **Frame Rendering (`onDrawFrame`)**:
 *     -   Clears the color buffer.
 *     -   Activates the shader program.
 *     -   Updates the bound texture with the latest `overlayBitmap`.
 *     -   Calculates the `homographyMatrix` based on the four corner points defined in the `uiState`.
 *         This matrix is responsible for the perspective warping of the quad.
 *     -   Passes all data to the shaders:
 *         -   Vertex positions and texture coordinates as attributes.
 *         -   The MVP matrix, homography matrix, opacity, contrast, and saturation as uniforms.
 *     -   Draws the quad using `glDrawElements`.
 *     -   Disables the vertex attribute arrays.
 *
 * @property context The application context, used for accessing assets like shaders.
 */
class MuralRenderer(private val context: Context) : GLSurfaceView.Renderer {

    /** A [FloatBuffer] to hold the vertex coordinates of the quad. */
    private lateinit var vertexBuffer: FloatBuffer
    /** A [FloatBuffer] to hold the texture coordinates (UVs) corresponding to each vertex. */
    private lateinit var uvBuffer: FloatBuffer
    /** A [ShortBuffer] defining the order in which to draw the vertices to form two triangles (a quad). */
    private lateinit var indexBuffer: ShortBuffer

    /**
     * The vertices of the quad in normalized device coordinates (NDC).
     * The quad spans from -1 to 1 on both axes, covering the entire screen.
     */
    private val vertices = floatArrayOf(
        -1.0f, -1.0f, 0.0f,  // bottom left
         1.0f, -1.0f, 0.0f,  // bottom right
        -1.0f,  1.0f, 0.0f,  // top left
         1.0f,  1.0f, 0.0f   // top right
    )

    /**
     * The texture coordinates (UVs) for the quad's vertices.
     * These map the corners of the bitmap texture to the corners of the quad.
     * (0,0) is top-left, (1,1) is bottom-right in texture space.
     */
    private val uvs = floatArrayOf(
        0.0f, 1.0f, // Corresponds to bottom-left vertex
        1.0f, 1.0f, // Corresponds to bottom-right vertex
        0.0f, 0.0f, // Corresponds to top-left vertex
        1.0f, 0.0f  // Corresponds to top-right vertex
    )

    /** The indices to draw the two triangles that form the quad. */
    private val indices = shortArrayOf(0, 1, 2, 1, 3, 2)

    /** The handle to the compiled and linked OpenGL shader program. */
    private var program: Int = 0
    /** The handle for the OpenGL texture that will hold the `overlayBitmap`. */
    private var textureId: Int = 0
    /** The bitmap image to be rendered as a texture on the quad. Updated via [setOverlayBitmap]. */
    private var overlayBitmap: Bitmap? = null
    /** The current state of the UI, containing values for opacity and corner points. Updated via [setUiState]. */
    private var uiState: UiState? = null

    /** The Model-View-Projection (MVP) matrix, used to project the 3D quad onto the 2D screen. */
    private val mvpMatrix = FloatArray(16)
    /** A 3x3 matrix used to apply perspective warping to the texture coordinates in the vertex shader. */
    private val homographyMatrix = FloatArray(9)

    /**
     * Updates the renderer with the latest UI state from the ViewModel.
     * This method is called from the UI thread to pass down state changes (e.g., slider values
     * for opacity, or updated corner point positions for homography).
     *
     * @param newUiState The new [UiState] to be used for rendering the next frame.
     */
    fun setUiState(newUiState: UiState) {
        this.uiState = newUiState
    }

    /**
     * Updates the renderer with the overlay image to be drawn.
     * This is called when the user selects a new image to place on the mural.
     *
     * @param bitmap The [Bitmap] of the overlay image. Can be null if no image is selected,
     *               in which case nothing will be drawn.
     */
    fun setOverlayBitmap(bitmap: Bitmap?) {
        this.overlayBitmap = bitmap
    }

    /**
     * Called once to set up the view's OpenGL ES environment.
     * This is where shaders are compiled and linked, buffer objects are initialized,
     * and the texture is configured. This method runs on the GL thread.
     *
     * @param gl The GL10 interface (not used in ES 2.0).
     * @param config The EGLConfig for the surface.
     */
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Set the background color to transparent to see the camera feed or image behind it.
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        setupBuffers()
        setupShaders()
        setupTexture()
    }

    /**
     * Called if the geometry of the view changes, such as when the device's screen orientation changes.
     * This is where the OpenGL viewport and projection matrix are updated to match the new dimensions.
     *
     * @param gl The GL10 interface (not used).
     * @param width The new width of the surface.
     * @param height The new height of the surface.
     */
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // Set up an orthographic projection since we are drawing a simple 2D quad.
        android.opengl.Matrix.orthoM(mvpMatrix, 0, -1f, 1f, -1f, 1f, -1f, 1f)
    }

    /**
     * Called for each redraw of the view. This is the main rendering loop.
     * It checks for valid state and bitmap, binds the shaders, updates all uniforms
     * (matrices, opacity, etc.), and draws the geometry for the current frame.
     *
     * @param gl The GL10 interface (not used).
     */
    override fun onDrawFrame(gl: GL10?) {
        val currentState = uiState ?: return
        val currentBitmap = overlayBitmap ?: return

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Update the texture with the bitmap content for the current frame.
        updateTexture(currentBitmap)
        // Recalculate the homography matrix based on the user-manipulated points.
        calculateHomography(currentState, currentBitmap)

        // Pass vertex data to the shader.
        val positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        // Pass texture coordinate data to the shader.
        val texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoordinate")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, uvBuffer)

        // Pass matrices to the shader.
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        val homographyMatrixHandle = GLES20.glGetUniformLocation(program, "u_HomographyMatrix")
        GLES20.glUniformMatrix3fv(homographyMatrixHandle, 1, false, homographyMatrix, 0)

        // Bind the texture and pass it to the shader.
        val textureHandle = GLES20.glGetUniformLocation(program, "u_Texture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        // Pass UI-controlled style parameters to the fragment shader.
        val opacityHandle = GLES20.glGetUniformLocation(program, "u_Opacity")
        GLES20.glUniform1f(opacityHandle, currentState.opacity)

        val contrastHandle = GLES20.glGetUniformLocation(program, "u_Contrast")
        GLES20.glUniform1f(contrastHandle, currentState.contrast)

        val saturationHandle = GLES20.glGetUniformLocation(program, "u_Saturation")
        GLES20.glUniform1f(saturationHandle, currentState.saturation)

        // Draw the quad.
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        // Clean up by disabling the vertex arrays.
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * Initializes the NIO buffers for vertices, UVs, and indices.
     * These buffers are used to pass geometry data to the GPU efficiently.
     */
    private fun setupBuffers() {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        uvBuffer = ByteBuffer.allocateDirect(uvs.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(uvs); position(0) }
        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
    }

    /**
     * Loads shader source code from assets, compiles them, and links them into a single program.
     */
    private fun setupShaders() {
        val vertexShaderCode = context.assets.open("shaders/vertex_shader.glsl").bufferedReader().use { it.readText() }
        val fragmentShaderCode = context.assets.open("shaders/fragment_shader.glsl").bufferedReader().use { it.readText() }
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    /**
     * Generates a texture handle and sets its filtering and wrapping parameters.
     */
    private fun setupTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        // Use linear filtering for a smoother appearance.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Clamp to edge to prevent texture artifacts at the borders.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /**
     * Updates the active texture with the contents of the provided bitmap.
     * @param bitmap The [Bitmap] to load into the texture memory.
     */
    private fun updateTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /**
     * Calculates the homography matrix needed for perspective warping.
     * It maps the four corners of the source bitmap to the four destination points
     * provided by the [UiState]. If the points are not available, it uses an identity matrix.
     *
     * @param uiState The current UI state containing the destination points.
     * @param bitmap The source bitmap, used to get the dimensions for the source points.
     */
    private fun calculateHomography(uiState: UiState, bitmap: Bitmap) {
        if (uiState.points.size == 4) {
            // Source points are the four corners of the bitmap.
            val src = floatArrayOf(
                0f, 0f, // top left
                bitmap.width.toFloat(), 0f, // top right
                bitmap.width.toFloat(), bitmap.height.toFloat(), // bottom right
                0f, bitmap.height.toFloat() // bottom left
            )
            // Destination points are from the UI, manipulated by the user.
            val dst = uiState.points.flatMap { listOf(it.x, it.y) }.toFloatArray()
            val matrix = android.graphics.Matrix()
            // Calculate the matrix that maps the source points to the destination points.
            matrix.setPolyToPoly(src, 0, dst, 0, 4)
            matrix.getValues(homographyMatrix)
        } else {
            // If no points are set, use an identity matrix (no warping).
            android.graphics.Matrix().getValues(homographyMatrix)
        }
    }

    /**
     * A utility function to compile a shader from source code.
     * @param type The type of shader (e.g., [GLES20.GL_VERTEX_SHADER]).
     * @param shaderCode The GLSL source code for the shader.
     * @return The handle to the compiled shader.
     */
    private fun loadShader(type: Int, shaderCode: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            // TODO: Add error checking for shader compilation.
        }
}