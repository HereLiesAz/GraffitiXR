package com.hereliesaz.graffitixr.slam

import java.nio.ByteBuffer

/**
 * Manager for the ORB-SLAM3 system via JNI.
 */
class SlamManager {

    companion object {
        init {
            try {
                System.loadLibrary("graffiti_slam")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
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
