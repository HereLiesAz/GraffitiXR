package com.hereliesaz.graffitixr.slam

import com.google.ar.core.Pose
import com.google.ar.core.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class SlamManager {

    private val _mappingQuality = MutableStateFlow(0.0f)
    val mappingQuality = _mappingQuality.asStateFlow()

    // Instance Handle
    private var nativeHandle: Long = 0
    private val lock = Any()

    companion object {
        init {
            try {
                System.loadLibrary("graffitixr")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    fun initNative() {
        synchronized(lock) {
            if (nativeHandle == 0L) {
                nativeHandle = initNativeJni()
            }
        }
    }

    fun destroyNative() {
        synchronized(lock) {
            if (nativeHandle != 0L) {
                destroyNativeJni(nativeHandle)
                nativeHandle = 0L
            }
        }
    }

    fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray) {
        if (nativeHandle != 0L) updateCameraJni(nativeHandle, viewMtx, projMtx)
    }

    fun feedDepthData(buffer: ByteBuffer, width: Int, height: Int) {
        if (nativeHandle != 0L && buffer.isDirect) {
            feedDepthDataJni(nativeHandle, buffer, width, height)
        }
    }

    fun draw() {
        if (nativeHandle != 0L) drawJni(nativeHandle)
    }

    fun getPointCount(): Int {
        return if (nativeHandle != 0L) getPointCountJni(nativeHandle) else 0
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        if (nativeHandle != 0L) onSurfaceChangedJni(nativeHandle, width, height)
    }

    fun alignMap(transformMatrix: FloatArray) {
        if (nativeHandle != 0L && transformMatrix.size == 16) {
            alignMapJni(nativeHandle, transformMatrix)
        }
    }

    fun updateFeatureMapQuality(session: Session, pose: Pose) {
        val count = getPointCount()
        val quality = (count / 5000f).coerceIn(0f, 1f)
        _mappingQuality.value = quality
    }

    fun saveWorld(path: String): Boolean {
        return if (nativeHandle != 0L) saveWorld(nativeHandle, path) else false
    }

    fun loadWorld(path: String): Boolean {
        return if (nativeHandle != 0L) loadWorld(nativeHandle, path) else false
    }

    // Native Interface (Updated Signatures)
    private external fun initNativeJni(): Long
    private external fun destroyNativeJni(handle: Long)
    private external fun updateCameraJni(handle: Long, viewMtx: FloatArray, projMtx: FloatArray)
    private external fun feedDepthDataJni(handle: Long, buffer: ByteBuffer, width: Int, height: Int)
    private external fun drawJni(handle: Long)
    private external fun getPointCountJni(handle: Long): Int
    private external fun onSurfaceChangedJni(handle: Long, width: Int, height: Int)
    private external fun alignMapJni(handle: Long, transformMtx: FloatArray)
    private external fun saveWorld(handle: Long, path: String): Boolean
    private external fun loadWorld(handle: Long, path: String): Boolean
}