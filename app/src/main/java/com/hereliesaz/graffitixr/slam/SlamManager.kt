package com.hereliesaz.graffitixr.slam

import android.util.Log

class SlamManager {

    // Lifecycle
    external fun initNative()
    external fun destroyNative()

    // Sensors
    external fun updateCamera(viewMtx: FloatArray, projMtx: FloatArray)

    // Updated: matches feedImage in JNI
    external fun feedImage(imageData: ByteArray, width: Int, height: Int)

    external fun feedDepth(depthData: ByteArray, width: Int, height: Int)

    // Rendering
    external fun drawFrame()

    // IO
    external fun saveWorld(path: String): Boolean
    external fun loadWorld(path: String): Boolean
    external fun clearMap()

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