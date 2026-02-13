package com.hereliesaz.graffitixr.core.native

import android.graphics.Bitmap

object GraffitiJNI {
    init {
        System.loadLibrary("graffitixr_native")
        System.loadLibrary("opencv_java4")
    }

    /**
     * Initialize the native engine.
     */
    external fun init(width: Int, height: Int)

    /**
     * Cleanup native resources.
     */
    external fun cleanup()

    /**
     * Main update loop.
     * @param matAddr Address of the OpenCV Mat (Camera Frame)
     * @param viewMatrix 4x4 View Matrix
     * @param projMatrix 4x4 Projection Matrix
     */
    external fun update(matAddr: Long, viewMatrix: FloatArray, projMatrix: FloatArray)

    // --- Teleological Methods ---

    /**
     * Pushes the "Goal" fingerprints to the SLAM engine.
     * If these features are seen, the engine will assume they are valid map updates.
     */
    external fun setTargetDescriptors(descriptorBytes: ByteArray, rows: Int, cols: Int, type: Int)

    /**
     * Extracts ORB descriptors from a Bitmap.
     * Returns the raw bytes of the cv::Mat.
     */
    external fun extractFeaturesFromBitmap(bitmap: Bitmap): ByteArray?

    /**
     * Returns metadata for the features extracted from the bitmap.
     * IntArray[0] = rows
     * IntArray[1] = cols
     * IntArray[2] = type
     */
    external fun extractFeaturesMeta(bitmap: Bitmap): IntArray?
}