package com.hereliesaz.graffitixr.graphics

import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.ORB

/**
 * A class that uses OpenCV's ORB algorithm to detect and describe features in an image.
 *
 * This class provides a method to find distinctive keypoints and compute their descriptors,
 * which can be used to create a unique "fingerprint" of a scene for persistent tracking.
 */
class MarkerDetector {

    private val orb: ORB = ORB.create()

    /**
     * Detects and computes ORB features in the given image.
     *
     * @param image The input image in which to detect features, as an OpenCV [Mat].
     * @return A pair containing the [MatOfKeyPoint] (the detected feature points) and a
     *         [Mat] (their corresponding descriptors).
     */
    fun detectAndCompute(image: Mat): Pair<MatOfKeyPoint, Mat> {
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(image, Mat(), keypoints, descriptors)
        return keypoints to descriptors
    }
}