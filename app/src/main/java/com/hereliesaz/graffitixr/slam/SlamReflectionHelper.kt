package com.hereliesaz.graffitixr.slam

import android.util.Log
import com.hereliesaz.sphereslam.SphereSLAM
import java.lang.reflect.Method

/**
 * Helper class to interact with SphereSLAM via reflection.
 * This is necessary due to version discrepancies between local and CI environments
 * where processFrame() signature differs.
 */
object SlamReflectionHelper {

    private const val TAG = "SlamReflectionHelper"

    private var processFrameMethod: Method? = null
    private var saveMapMethod: Method? = null
    private var loadMapMethod: Method? = null
    private var savePhotosphereMethod: Method? = null

    fun initialize(slam: SphereSLAM) {
        val clazz = slam.javaClass

        // Resolve processFrame (Try 5-arg first, then 2-arg)
        try {
            processFrameMethod = clazz.getMethod(
                "processFrame",
                Long::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        } catch (e: NoSuchMethodException) {
            try {
                processFrameMethod = clazz.getMethod(
                    "processFrame",
                    Long::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType
                )
            } catch (e2: Exception) {
                Log.e(TAG, "processFrame method not found")
            }
        }

        // Resolve other methods safely, though they are expected to exist in standard version
        try {
            saveMapMethod = clazz.getMethod("saveMap", String::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "saveMap method not found: ${e.message}")
        }

        try {
            loadMapMethod = clazz.getMethod("loadMap", String::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "loadMap method not found: ${e.message}")
        }

        try {
            savePhotosphereMethod = clazz.getMethod("savePhotosphere", String::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "savePhotosphere method not found: ${e.message}")
        }
    }

    fun processFrame(slam: SphereSLAM, handle: Long, timestamp: Double, width: Int = 0, height: Int = 0, stride: Int = 0) {
        processFrameMethod?.let { method ->
            try {
                if (method.parameterCount == 5) {
                    method.invoke(slam, handle, timestamp, width, height, stride)
                } else {
                    method.invoke(slam, handle, timestamp)
                }
            } catch (e: Exception) {
                 // Log.e(TAG, "Error in processFrame", e) // Uncomment for debugging, but suppress for performance
            }
        }
    }

    fun saveMap(slam: SphereSLAM, path: String): Boolean {
        return try {
            saveMapMethod?.invoke(slam, path)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving map", e)
            false
        }
    }

    fun loadMap(slam: SphereSLAM, path: String): Boolean {
        return try {
            loadMapMethod?.invoke(slam, path)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading map", e)
            false
        }
    }

    fun savePhotosphere(slam: SphereSLAM, path: String): Boolean {
        return try {
            savePhotosphereMethod?.invoke(slam, path)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photosphere", e)
            false
        }
    }
}
