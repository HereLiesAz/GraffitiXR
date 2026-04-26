package com.hereliesaz.graffitixr.common.util

import android.util.Log
import org.opencv.android.OpenCVLoader

object NativeLibLoader {
    private var isLoaded = false

    @Synchronized
    fun loadAll() {
        if (isLoaded) return
        
        try {
            // Load OpenCV Java JNI wrapper
            if (OpenCVLoader.initLocal()) {
                Log.d("NativeLibLoader", "OpenCV loaded successfully.")
            } else {
                Log.e("NativeLibLoader", "OpenCV initialization failed via OpenCVLoader!")
            }

            // Load our primary C++ engine
            System.loadLibrary("graffitixr")
            Log.d("NativeLibLoader", "libgraffitixr.so loaded successfully.")
            
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeLibLoader", "Critical failure: native libraries could not be loaded!", e)
        }
    }
}
