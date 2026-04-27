package com.hereliesaz.graffitixr.common.util

import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicBoolean

object NativeLibLoader {
    private val isLoaded = AtomicBoolean(false)

    @Synchronized
    fun loadAll() {
        if (isLoaded.get()) return
        
        try {
            // Step 1: Ensure OpenCV is loaded. GraffitiXR depends on its native symbols.
            // Priority 1: Try exact versioned name (v4)
            // Priority 2: Try generic name
            // Priority 3: Try OpenCVLoader.initLocal()
            val opencvLoaded = try {
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

            if (!opencvLoaded) {
                val errorMsg = "CRITICAL: OpenCV native symbols could not be registered."
                Log.e("NativeLibLoader", errorMsg)
                throw RuntimeException(errorMsg)
            }

            // Step 2: Load our primary C++ engine (depends on symbols from Step 1)
            System.loadLibrary("graffitixr")
            Log.i("NativeLibLoader", "libgraffitixr.so loaded successfully.")
            
            // Only set to true if BOTH loaded successfully
            isLoaded.set(true)
        } catch (e: UnsatisfiedLinkError) {
            val errorMsg = "CRITICAL: Native libraries could not be loaded!"
            Log.e("NativeLibLoader", errorMsg, e)
            throw RuntimeException("$errorMsg ${e.message}", e)
        }
    }
}
