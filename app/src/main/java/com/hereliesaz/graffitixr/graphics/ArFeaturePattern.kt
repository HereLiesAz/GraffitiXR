package com.hereliesaz.graffitixr.graphics

import org.opencv.core.Mat

/**
 * A data class that represents the unique "fingerprint" of a locked AR scene.
 *
 * This class stores the data needed to recognize a previously seen area and re-establish
 * a persistent anchor for the AR overlay.
 *
 * @property descriptors An OpenCV [Mat] where each row is a descriptor (e.g., from ORB)
 *                       for a detected feature point.
 * @property worldPoints A list of 3D points (as `FloatArray` of size 3), where each point
 *                       corresponds to the row at the same index in the `descriptors` matrix.
 */
data class ArFeaturePattern(
    val descriptors: Mat,
    val worldPoints: List<FloatArray>
)