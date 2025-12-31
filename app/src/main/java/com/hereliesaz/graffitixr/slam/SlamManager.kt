package com.hereliesaz.graffitixr.slam

import android.app.Activity
import android.util.Log

/**
 * Manages the SLAM (Simultaneous Localization and Mapping) system using ORB-SLAM3.
 * This class serves as a replacement for the `eq-slam` library's `SlamCore`.
 */
class SlamManager(private val activity: Activity) {

    companion object {
        private const val TAG = "SlamManager"

        init {
            try {
                System.loadLibrary("graffiti_slam")
                Log.i(TAG, "Native library 'graffiti_slam' loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'graffiti_slam'.", e)
            }
        }
    }

    /**
     * Initializes the SLAM system.
     */
    fun init() {
        Log.d(TAG, "Initializing SLAM system...")
        initNative()
    }

    /**
     * Resumes the SLAM system.
     */
    fun resume() {
        Log.d(TAG, "Resuming SLAM system...")
        // Call native resume if needed
    }

    /**
     * Pauses the SLAM system.
     */
    fun pause() {
        Log.d(TAG, "Pausing SLAM system...")
        // Call native pause if needed
    }

    /**
     * Stops and disposes of the SLAM system.
     */
    fun dispose() {
        Log.d(TAG, "Disposing SLAM system...")
        disposeNative()
    }

    /**
     * Saves the current map.
     */
    fun saveMap() {
        Log.d(TAG, "Saving map...")
        saveMapNative()
    }

    /**
     * Processes a frame for SLAM.
     * @param width The width of the image.
     * @param height The height of the image.
     * @param data The image data (e.g., grayscale buffer).
     * @param timestamp The timestamp of the frame in nanoseconds.
     */
    fun processFrame(width: Int, height: Int, data: ByteArray, timestamp: Long) {
        processFrameNative(width, height, data, timestamp)
    }

    // Native methods
    private external fun initNative()
    private external fun disposeNative()
    private external fun saveMapNative()
    private external fun processFrameNative(width: Int, height: Int, data: ByteArray, timestamp: Long)
}
