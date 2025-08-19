package com.hereliesaz.graffitixr

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.graffitixr.rendering.BackgroundRenderer
import com.hereliesaz.graffitixr.rendering.ObjectRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.content.Context

import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import android.opengl.Matrix
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.hereliesaz.graffitixr.rendering.GpuBuffer
import com.hereliesaz.graffitixr.rendering.IndexBuffer
import com.hereliesaz.graffitixr.rendering.Mesh
import com.hereliesaz.graffitixr.rendering.Shader
import com.hereliesaz.graffitixr.rendering.Texture
import com.hereliesaz.graffitixr.rendering.VertexBuffer
import com.hereliesaz.graffitixr.rendering.floatArrayToByteArray
import com.hereliesaz.graffitixr.rendering.shortArrayToByteArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MuralRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val assets: AssetManager = context.assets
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var muralObject: ObjectRenderer
    private lateinit var muralShader: Shader
    private lateinit var muralTexture: Texture
    private lateinit var depthTexture: Texture
    private lateinit var muralMesh: Mesh
    private lateinit var depthTexCoordBuffer: VertexBuffer

    private var session: Session? = null
    private val trackedImages = mutableMapOf<Int, AugmentedImage>()
    private val stoppedMarkers = mutableListOf<Int>()
    private var currentState = MuralState()
    private lateinit var augmentedImageDatabase: AugmentedImageDatabase

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        augmentedImageDatabase = AugmentedImageDatabase(session)
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer.createOnGlThread(assets)

        muralObject = ObjectRenderer()
        muralShader = Shader(assets, "shaders/mural_object.vert", "shaders/mural_object.frag", null)
        muralTexture = Texture()
        depthTexture = Texture()
        muralMesh = createMuralMesh()
    }

    private fun createMuralMesh(): Mesh {
        val quadCoords = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val quadTexCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val quadIndices = shortArrayOf(0, 1, 2, 1, 3, 2)
        val quadDepthTexCoords = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        val vertexBuffer = VertexBuffer(GpuBuffer(GLES30.GL_ARRAY_BUFFER, quadCoords.size * 4, floatArrayToByteArray(quadCoords)), 3)
        val texCoordBuffer = VertexBuffer(GpuBuffer(GLES30.GL_ARRAY_BUFFER, quadTexCoords.size * 4, floatArrayToByteArray(quadTexCoords)), 2)
        depthTexCoordBuffer = VertexBuffer(GpuBuffer(GLES30.GL_ARRAY_BUFFER, quadDepthTexCoords.size * 4, floatArrayToByteArray(quadDepthTexCoords)), 2)
        val indexBuffer = IndexBuffer(GpuBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, quadIndices.size * 2, shortArrayToByteArray(quadIndices)))
        return Mesh(vertexBuffer, indexBuffer, texCoordBuffer, depthTexCoordBuffer)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        session?.let {
            it.setCameraTextureName(backgroundRenderer.getTextureId())
            val frame = it.update()
            backgroundRenderer.draw(frame)
            val camera = frame.camera

            val updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage::class.java)
            var markersChanged = false
            for (img in updatedAugmentedImages) {
                if (img.trackingState == TrackingState.TRACKING) {
                    if (!trackedImages.containsKey(img.index)) {
                        trackedImages[img.index] = img
                        markersChanged = true
                    }
                } else if (img.trackingState == TrackingState.STOPPED) {
                    if (trackedImages.containsKey(img.index)) {
                        trackedImages.remove(img.index)
                        stoppedMarkers.add(img.index)
                        markersChanged = true
                    }
                }
            }

            if (markersChanged) {
                recalculateMuralQuad(frame)
            }

            if (camera.trackingState == TrackingState.TRACKING && trackedImages.isNotEmpty()) {
                try {
                    frame.acquireDepthImage16Bits().use { depthImage ->
                        val plane = depthImage.planes[0]
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture.getTextureId())
                        GLES30.glTexImage2D(
                            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, depthImage.width, depthImage.height, 0,
                            GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, plane.buffer
                        )
                    }
                } catch (e: Exception) {
                    // Depth not available yet.
                }

                val modelMatrix = FloatArray(16)
                Matrix.setIdentityM(modelMatrix, 0)
                muralObject.draw(muralMesh, muralShader, muralTexture, depthTexture, camera, modelMatrix, currentState)
            }
        }
    }

    fun getStoppedMarkerIndices(): List<Int> {
        val result = stoppedMarkers.toList()
        stoppedMarkers.clear()
        return result
    }

    private fun recalculateMuralQuad(frame: Frame) {
        if (trackedImages.size < 2) return

        val camera = frame.camera
        val referenceImage = trackedImages.values.first()
        val referenceTransform = FloatArray(16)
        referenceImage.centerPose.toMatrix(referenceTransform, 0)

        val inverseReferenceTransform = FloatArray(16)
        Matrix.invertM(inverseReferenceTransform, 0, referenceTransform, 0)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (image in trackedImages.values) {
            val point = image.centerPose.translation
            val localPoint = FloatArray(4)
            Matrix.multiplyMV(localPoint, 0, inverseReferenceTransform, 0, floatArrayOf(point[0], point[1], point[2], 1f), 0)

            minX = minOf(minX, localPoint[0])
            minY = minOf(minY, localPoint[1])
            maxX = maxOf(maxX, localPoint[0])
            maxY = maxOf(maxY, localPoint[1])
        }

        val corner1Local = floatArrayOf(minX, minY, 0f, 1f)
        val corner2Local = floatArrayOf(maxX, minY, 0f, 1f)
        val corner3Local = floatArrayOf(minX, maxY, 0f, 1f)
        val corner4Local = floatArrayOf(maxX, maxY, 0f, 1f)

        val corner1World = FloatArray(4)
        val corner2World = FloatArray(4)
        val corner3World = FloatArray(4)
        val corner4World = FloatArray(4)

        Matrix.multiplyMV(corner1World, 0, referenceTransform, 0, corner1Local, 0)
        Matrix.multiplyMV(corner2World, 0, referenceTransform, 0, corner2Local, 0)
        Matrix.multiplyMV(corner3World, 0, referenceTransform, 0, corner3Local, 0)
        Matrix.multiplyMV(corner4World, 0, referenceTransform, 0, corner4Local, 0)

        val vertices = floatArrayOf(
            corner1World[0], corner1World[1], corner1World[2],
            corner2World[0], corner2World[1], corner2World[2],
            corner3World[0], corner3World[1], corner3World[2],
            corner4World[0], corner4World[1], corner4World[2]
        )

        muralMesh.vertexBuffer.set(vertices)

        val viewMatrix = FloatArray(16)
        val projMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
        camera.getViewMatrix(viewMatrix, 0)

        val vpMatrix = FloatArray(16)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        val ndcCoords = FloatArray(8)
        for (i in 0 until 4) {
            val worldPoint = floatArrayOf(vertices[i*3], vertices[i*3+1], vertices[i*3+2], 1f)
            val ndcPoint = FloatArray(4)
            Matrix.multiplyMV(ndcPoint, 0, vpMatrix, 0, worldPoint, 0)
            ndcCoords[i*2] = ndcPoint[0] / ndcPoint[3]
            ndcCoords[i*2+1] = ndcPoint[1] / ndcPoint[3]
        }

        val ndcBuffer = ByteBuffer.allocateDirect(ndcCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        ndcBuffer.put(ndcCoords)
        ndcBuffer.position(0)

        val depthUvBuffer = ByteBuffer.allocateDirect(ndcCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndcBuffer,
            Coordinates2d.TEXTURE_NORMALIZED,
            depthUvBuffer
        )

        val depthUvCoords = FloatArray(depthUvBuffer.capacity())
        depthUvBuffer.get(depthUvCoords)

        depthTexCoordBuffer.set(depthUvCoords)
    }

    fun setSession(session: Session) {
        this.session = session
    }

    fun getTrackedImageCount(): Int {
        return trackedImages.size
    }

    fun updateState(state: MuralState) {
        if (currentState.markers.size != state.markers.size) {
            augmentedImageDatabase = AugmentedImageDatabase(session)
            state.markers.forEachIndexed { index, marker ->
                augmentedImageDatabase.addImage("marker_$index", marker)
            }
            val config = Config(session)
            config.augmentedImageDatabase = augmentedImageDatabase

            if (session?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }

            session?.configure(config)
        }

        this.currentState = state

        if (state.imageUri != null) {
            try {
                val bitmap = context.contentResolver.openInputStream(state.imageUri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                if (bitmap != null) {
                    muralObject.updateTexture(muralTexture, bitmap)
                }
            } catch (e: Exception) {
                // Log error
            }
        }
    }
}