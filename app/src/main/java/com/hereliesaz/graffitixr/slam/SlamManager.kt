package com.hereliesaz.graffitixr.slam

import com.google.ar.core.Pose
import com.google.ar.core.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class SlamManager {

    private val _mappingQuality = MutableStateFlow(0.0f)
    val mappingQuality = _mappingQuality.asStateFlow()

    companion object {
        init {
            try {
                System.loadLibrary("graffitixr")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }

        private var refCount = 0
        private val lock = Any()
    }

    fun initNative() {
        synchronized(lock) {
            if (refCount == 0) {
                initNativeJni()
            }
            refCount++
        }
    }

    fun destroyNative() {
        synchronized(lock) {
            if (refCount > 0) {
                refCount--
                if (refCount == 0) {
                    destroyNativeJni()
                }
            }
        }
    }

    fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray) {
        updateCameraJni(viewMtx, projMtx)
    }

    // New Method: Feed raw depth buffer
    fun feedDepthData(buffer: ByteBuffer, width: Int, height: Int) {
        if (!buffer.isDirect) return
        feedDepthDataJni(buffer, width, height)
    }

    fun draw() {
        drawJni()
    }

    fun getPointCount(): Int {
        return getPointCountJni()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        onSurfaceChangedJni(width, height)
    }

    fun alignMap(transformMatrix: FloatArray) {
        if (transformMatrix.size != 16) return
        alignMapJni(transformMatrix)
    }

    fun updateFeatureMapQuality(session: Session, pose: Pose) {
        val count = getPointCount()
        val quality = (count / 5000f).coerceIn(0f, 1f)
        _mappingQuality.value = quality
    }

    external fun saveWorld(path: String): Boolean
    external fun loadWorld(path: String): Boolean

    private external fun initNativeJni()
    private external fun destroyNativeJni()
    private external fun updateCameraJni(viewMtx: FloatArray, projMtx: FloatArray)
    private external fun feedDepthDataJni(buffer: ByteBuffer, width: Int, height: Int) // New JNI
    private external fun drawJni()
    private external fun getPointCountJni(): Int
    private external fun onSurfaceChangedJni(width: Int, height: Int)
    private external fun alignMapJni(transformMtx: FloatArray)
}