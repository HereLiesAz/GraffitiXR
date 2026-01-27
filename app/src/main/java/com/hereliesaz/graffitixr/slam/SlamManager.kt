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

    // Lifecycle
    private external fun initNativeJni()
    private external fun destroyNativeJni()

    fun initNative() {
        synchronized(Companion) {
            if (refCount == 0) {
                initNativeJni()
            }
            refCount++
        }
    }

    fun destroyNative() {
        synchronized(Companion) {
            if (refCount > 0) {
                refCount--
                if (refCount == 0) {
                    destroyNativeJni()
                }
            }
        }
    }

    // Sensors
    external fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray)
    external fun updateCameraImage(imageData: ByteArray, width: Int, height: Int, timestamp: Long)
    external fun feedDepth(depthData: ByteArray, width: Int, height: Int)

    // Rendering
    external fun drawFrame()

    // IO
    external fun saveWorld(path: String): Boolean
    external fun loadWorld(path: String): Boolean
    external fun clearMap()
    
    // Metrics
    external fun getPointCount(): Int

    fun updateFeatureMapQuality(session: Session, pose: Pose) {
        val count = getPointCount()
        // Thresholds: < 1000 (Low), 1000-5000 (Medium), > 5000 (High)
        val quality = (count / 5000f).coerceIn(0f, 1f)
        _mappingQuality.value = quality
    }

    companion object {
        private var refCount = 0

        init {
            try {
                System.loadLibrary("graffiti-lib")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("SlamManager", "Failed to load native library: ${e.message}")
            }
        }
    }
}
