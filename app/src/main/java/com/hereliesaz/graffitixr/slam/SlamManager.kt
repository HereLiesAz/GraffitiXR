package com.hereliesaz.graffitixr.slam

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.ar.core.Session
import com.google.ar.core.Pose
import com.google.ar.core.Anchor

class SlamManager {

    private val _mappingQuality = MutableStateFlow(0f)
    val mappingQuality = _mappingQuality.asStateFlow()

    private val _isHosting = MutableStateFlow(false)
    val isHosting = _isHosting.asStateFlow()

    // Lifecycle
    external fun initNative()
    external fun initSLAM(vocabPath: String, settingsPath: String)
    external fun destroyNative()

    // Sensors
    external fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray)

    // Updated: matches feedImage in JNI
    external fun feedImage(imageData: ByteArray, width: Int, height: Int)

    external fun processFrameNative(width: Int, height: Int, data: ByteArray, timestamp: Long)

    external fun feedDepth(depthData: ByteArray, width: Int, height: Int)

    // Rendering
    external fun drawFrame()

    // IO
    external fun saveWorld(path: String): Boolean
    external fun loadWorld(path: String): Boolean
    external fun clearMap()

    fun updateFeatureMapQuality(session: Session, pose: Pose) {
        // Mock logic: calculate quality based on tracking state or random for now
        // In real impl, this would query native SLAM
        _mappingQuality.value = 1.0f // Mock perfect quality
    }

    suspend fun hostAnchor(session: Session, anchor: Anchor, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        _isHosting.value = true
        try {
            // Mock hosting
            kotlinx.coroutines.delay(1000)
            val cloudId = "mock-cloud-anchor-id-${System.currentTimeMillis()}"
            onSuccess(cloudId)
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        } finally {
            _isHosting.value = false
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("graffiti-lib")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("SlamManager", "Failed to load native library: ${e.message}")
            }
        }
    }
}
