package com.hereliesaz.graffitixr.slam

import android.util.Log

/**
 * Manager for the ORB-SLAM3 system via JNI.
 */
class SlamManager {

    companion object {
        init {
            try {
                // Ensure dependency library (OpenCV) is loaded before SLAM
                System.loadLibrary("opencv_java4")
                System.loadLibrary("graffiti_slam")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("SlamManager", "Failed to load SLAM native library: ${e.message}")
            } catch (e: Exception) {
                Log.e("SlamManager", "Error during native initialization: ${e.message}")
            }
        }
    }

    /**
     * Initializes the SLAM system with the given vocabulary and settings files.
     * @param vocPath Path to the ORB vocabulary file.
     * @param settingsPath Path to the settings file (YAML).
     */
    external fun initNative(vocPath: String, settingsPath: String)

    /**
     * Shuts down the SLAM system.
     */
    external fun disposeNative()

    /**
     * Saves the current map (Atlas).
     */
    external fun saveMapNative()

    /**
     * Processes a single frame.
     * @param width Width of the image.
     * @param height Height of the image.
     * @param data Raw image data (e.g. Grayscale or RGB).
     * @param timestamp Timestamp of the frame.
     */
    external fun processFrameNative(width: Int, height: Int, data: ByteArray, timestamp: Long)
}
