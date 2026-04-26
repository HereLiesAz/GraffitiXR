package com.hereliesaz.graffitixr.common.util

import android.util.Log
import org.opencv.android.OpenCVLoader

object NativeLibLoader {
    private var isLoaded = false

    @Synchronized
    fun loadAll() {
        if (isLoaded) return
        
        try {
            // Priority 1: Try exact versioned name (v4)
            // Priority 2: Try generic name
            // Priority 3: Try OpenCVLoader.initLocal()
            val loadSuccess = try {
                System.loadLibrary("opencv_java4")
                Log.i("NativeLibLoader", "libopencv_java4.so loaded directly.")
                true
            } catch (e: UnsatisfiedLinkError) {
                try {
                    System.loadLibrary("opencv_java")
                    Log.i("NativeLibLoader", "libopencv_java.so loaded directly.")
                    true
                } catch (e2: UnsatisfiedLinkError) {
                    Log.w("NativeLibLoader", "Direct load failed (${e.message} / ${e2.message}), trying OpenCVLoader fallback...")
                    OpenCVLoader.initLocal()
                }
            }

            if (!loadSuccess) {
                val errorMsg = "OpenCV initialization failed via all methods! Java JNI symbols will be missing."
                Log.e("NativeLibLoader", errorMsg)
                throw RuntimeException(errorMsg)
            }

            // Load our primary C++ engine
            System.loadLibrary("graffitixr")
            Log.i("NativeLibLoader", "libgraffitixr.so loaded successfully.")
            
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            val errorMsg = "CRITICAL: Native libraries could not be loaded!"
            Log.e("NativeLibLoader", errorMsg, e)
            throw RuntimeException("$errorMsg ${e.message}", e)
        }
    }
}
