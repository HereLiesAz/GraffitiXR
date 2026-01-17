package com.hereliesaz.graffitixr.slam

import android.graphics.Bitmap
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import com.google.ar.core.Session.FeatureMapQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Manager that bridges Native Dense Mapping (MobileGS) and Cloud Anchor Hosting.
 */
class SlamManager {

    // --- Native Interface (MobileGS) ---
    companion object {
        init {
            try {
                System.loadLibrary("graffiti-lib")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("SlamManager", "Native library not found: ${e.message}")
            }
        }
    }

    external fun initNative()
    external fun destroyNative()
    external fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray)

    external fun feedSensors(
        imageData: ByteArray,
        width: Int,
        height: Int,
        pointCloud: FloatArray,
        pointCount: Int
    )

    external fun feedDepth(
        depthData: ByteArray,
        width: Int,
        height: Int
    )

    external fun drawFrame()
    external fun saveWorld(path: String): Boolean
    external fun loadWorld(path: String): Boolean

    // Helper to feed bitmap data to C++
    fun feedFrame(bitmap: Bitmap, points: FloatArray) {
        val width = bitmap.width
        val height = bitmap.height
        val buffer = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)

        feedSensors(buffer.array(), width, height, points, points.size / 4)
    }

    // --- Cloud Anchor Logic (Restored) ---

    private val _mappingQuality = MutableStateFlow(FeatureMapQuality.INSUFFICIENT)
    val mappingQuality = _mappingQuality.asStateFlow()

    private val _isHosting = MutableStateFlow(false)
    val isHosting = _isHosting.asStateFlow()

    fun updateFeatureMapQuality(session: Session, cameraPose: com.google.ar.core.Pose) {
        try {
            val quality = session.estimateFeatureMapQualityForHosting(cameraPose)
            _mappingQuality.value = quality
        } catch (e: Exception) {
            // Session might be paused or not ready
        }
    }

    fun hostAnchor(session: Session, anchor: Anchor, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        _isHosting.value = true
        session.hostCloudAnchorAsync(anchor, 365) { cloudAnchorId, state ->
            _isHosting.value = false
            if (state == Anchor.CloudAnchorState.SUCCESS) {
                onSuccess(cloudAnchorId)
            } else {
                onError(state.toString())
            }
        }
    }

    fun reset() {
        _mappingQuality.value = FeatureMapQuality.INSUFFICIENT
        _isHosting.value = false
        // Note: We don't explicitly clear the native map here yet,
        // as that requires exposing a 'clear()' JNI method.
        // For now, this resets the UI state for Cloud Anchors.
    }
}