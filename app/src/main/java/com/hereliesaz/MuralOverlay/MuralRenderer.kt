package com.hereliesaz.MuralOverlay

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hereliesaz.MuralOverlay.rendering.BackgroundRenderer
import com.hereliesaz.MuralOverlay.rendering.ObjectRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.content.Context

import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import android.opengl.Matrix
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config

class MuralRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private val assets: AssetManager = context.assets
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var muralObject: ObjectRenderer
    private lateinit var muralShader: Shader
    private lateinit var muralTexture: Texture
    private lateinit var muralMesh: Mesh

    private var session: Session? = null
    private val trackedImages = mutableMapOf<Int, AugmentedImage>()
    private var currentState = MuralState()
    private var augmentedImageDatabase: AugmentedImageDatabase? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer = BackgroundRenderer()
        backgroundRenderer.createOnGlThread(assets)

        muralObject = ObjectRenderer()
        muralShader = Shader(assets, "shaders/mural_object.vert", "shaders/mural_object.frag", null)
        muralTexture = Texture()
        muralMesh = createMuralMesh()
    }

    private fun createMuralMesh(): Mesh {
        val quadCoords = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val quadTexCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        val quadIndices = shortArrayOf(0, 1, 2, 1, 3, 2)

        val vertexBuffer = VertexBuffer(GpuBuffer(GLES30.GL_ARRAY_BUFFER, quadCoords.size * 4, floatArrayToByteArray(quadCoords)), 3)
        val texCoordBuffer = VertexBuffer(GpuBuffer(GLES30.GL_ARRAY_BUFFER, quadTexCoords.size * 4, floatArrayToByteArray(quadTexCoords)), 2)
        val indexBuffer = IndexBuffer(GpuBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, quadIndices.size * 2, shortArrayToByteArray(quadIndices)))
        return Mesh(vertexBuffer, indexBuffer)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        session?.let {
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
                recalculateMuralQuad()
            }

            if (camera.trackingState == TrackingState.TRACKING && trackedImages.isNotEmpty()) {
                val modelMatrix = FloatArray(16)
                Matrix.setIdentityM(modelMatrix, 0)
                muralObject.draw(muralMesh, muralShader, muralTexture, camera, modelMatrix, currentState)
            }
        }
    }

    private val stoppedMarkers = mutableListOf<Int>()

    fun getStoppedMarkerIndices(): List<Int> {
        val result = stoppedMarkers.toList()
        stoppedMarkers.clear()
        return result
    }

    private fun recalculateMuralQuad() {
        if (trackedImages.size < 2) return

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
            for (marker in state.markers) {
                augmentedImageDatabase?.addImage("marker", marker)
            }
            val config = Config(session)
            config.augmentedImageDatabase = augmentedImageDatabase
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
