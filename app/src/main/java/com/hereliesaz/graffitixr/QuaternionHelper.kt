package com.hereliesaz.graffitixr

import org.apache.commons.math3.complex.Quaternion

object QuaternionHelper {

    /**
     * Averages a list of quaternions using Normalized Linear Interpolation (NLerp).
     * This method is simple and efficient, suitable for quaternions that are close to each other.
     *
     * @param quaternions The list of quaternions to average.
     * @return The averaged and normalized quaternion. Returns an identity quaternion if the list is empty.
     */
    fun averageQuaternions(quaternions: List<Quaternion>): Quaternion {
        if (quaternions.isEmpty()) {
            return Quaternion.IDENTITY
        }

        // Ensure all quaternions are in the same hemisphere for correct averaging
        val alignedQuaternions = mutableListOf<Quaternion>()
        val first = quaternions.first()
        alignedQuaternions.add(first)

        for (i in 1 until quaternions.size) {
            var q = quaternions[i]
            // If the dot product is negative, the quaternions are in opposite hemispheres.
            // Invert one of them to bring them to the same side.
            if (Quaternion.dotProduct(first, q) < 0) {
                q = q.conjugate
            }
            alignedQuaternions.add(q)
        }


        // Sum up all the quaternions
        var sum = Quaternion(0.0, 0.0, 0.0, 0.0)
        for (q in alignedQuaternions) {
            sum = Quaternion.add(sum, q)
        }

        // Normalize the sum to get the average
        return sum.normalize()
    }
}
