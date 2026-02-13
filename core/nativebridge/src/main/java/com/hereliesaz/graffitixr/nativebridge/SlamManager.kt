package com.hereliesaz.graffitixr.nativebridge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlamManager @Inject constructor(
    private val context: Context
) {
    private val nativeMutex = Mutex()
    private var isInitialized = false

    companion object {
        init {
            try {
                System.loadLibrary("graffitixr_native")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("SlamManager", "Failed to load native library: ${e.message}")
            }
        }
    }

    private external fun nativeInit(assetManager: Any): Boolean
    private external fun nativeResize(width: Int, height: Int)
    private external fun nativeUpdateCamera(viewMtx: FloatArray, projMtx: FloatArray)
    private external fun nativeDraw()
    private external fun nativeLoadMap(path: String): Boolean
    private external fun nativeSaveMap(path: String): Boolean
    private external fun nativeReset()

    suspend fun initialize(): Boolean {
        return nativeMutex.withLock {
            if (!isInitialized) {
                val assetManager = context.applicationContext.assets
                isInitialized = nativeInit(assetManager)
                if (isInitialized) {
                    Log.d("SlamManager", "Native Engine Initialized")
                }
            }
            isInitialized
        }
    }

    suspend fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray) {
        nativeMutex.withLock {
            if (isInitialized) {
                nativeUpdateCamera(viewMtx, projMtx)
            }
        }
    }

    suspend fun draw() {
        nativeMutex.withLock {
            if (isInitialized) {
                nativeDraw()
            }
        }
    }

    suspend fun resize(width: Int, height: Int) {
        nativeMutex.withLock {
            if (isInitialized) {
                nativeResize(width, height)
            }
        }
    }

    suspend fun saveMap(path: String): Boolean {
        return nativeMutex.withLock {
            if (isInitialized) {
                nativeSaveMap(path)
            } else false
        }
    }

    suspend fun loadMap(path: String): Boolean {
        return nativeMutex.withLock {
            if (isInitialized) {
                nativeLoadMap(path)
            } else false
        }
    }

    suspend fun reset() {
        nativeMutex.withLock {
            if (isInitialized) {
                nativeReset()
                isInitialized = false
            }
        }
    }
}
