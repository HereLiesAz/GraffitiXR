package com.hereliesaz.graffitixr.natives

import android.content.res.AssetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JNI Bridge to the MobileGS C++ Engine.
 * * Removed: SphereSLAM legacy references.
 * Target: mobile_gs_jni
 */
class SlamManager {

    companion object {
        init {
            System.loadLibrary("mobile_gs_jni")
        }
    }

    private val _mappingQuality = MutableStateFlow(0f)
    val mappingQuality: StateFlow<Float> = _mappingQuality.asStateFlow()

    /**
     * Initialize the native engine.
     * @param assetManager Access to shader files and default assets.
     */
    external fun init(assetManager: AssetManager)

    /**
     * Resize the viewport.
     */
    external fun onSurfaceChanged(width: Int, height: Int)

    /**
     * Get the OpenGL texture ID for the camera feed.
     */
    external fun getExternalTextureId(): Int

    /**
     * Update the system with the latest ARCore frame data.
     * @param timestampNs Frame timestamp.
     * @param position Camera position (x, y, z).
     * @param rotation Camera rotation (x, y, z, w quaternion).
     */
    external fun update(timestampNs: Long, position: FloatArray, rotation: FloatArray)

    /**
     * Render the scene.
     * @param renderPointCloud Debug flag to render the sparse point cloud.
     */
    external fun draw(renderPointCloud: Boolean)

    /**
     * Reset the mapping system.
     */
    external fun reset()

    /**
     * Clean up native resources.
     */
    external fun destroy()

    /**
     * Save the current world map to a file.
     */
    external fun saveWorld(path: String): Boolean
}
