package com.hereliesaz.graffitixr.graphics

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * A data class that represents the unique "fingerprint" of a locked AR scene.
 *
 * This class stores the data needed to recognize a previously seen area and re-establish
 * a persistent anchor for the AR overlay. It is Parcelable to allow it to be saved
 * as part of the application's state.
 *
 * @property descriptors An OpenCV [Mat] where each row is a descriptor (e.g., from ORB)
 *                       for a detected feature point.
 * @property worldPoints A list of 3D points (as `FloatArray` of size 3), where each point
 *                       corresponds to the row at the same index in the `descriptors` matrix.
 */
@Parcelize
data class ArFeaturePattern(
    val descriptors: Mat,
    val worldPoints: List<FloatArray>
) : Parcelable {
    /**
     * Custom Parceler for [ArFeaturePattern] to handle the serialization of the OpenCV [Mat] object.
     */
    companion object : Parceler<ArFeaturePattern> {
        override fun create(parcel: Parcel): ArFeaturePattern {
            // Read descriptors Mat
            val rows = parcel.readInt()
            val cols = parcel.readInt()
            val type = parcel.readInt()
            val matData = ByteArray(parcel.readInt())
            parcel.readByteArray(matData)
            val descriptors = Mat(rows, cols, type)
            if(rows > 0 && cols > 0) {
                descriptors.put(0, 0, matData)
            }

            // Read world points
            val pointsList = mutableListOf<FloatArray>()
            parcel.readList(pointsList as List<*>, FloatArray::class.java.classLoader)

            return ArFeaturePattern(descriptors, pointsList)
        }

        override fun ArFeaturePattern.write(parcel: Parcel, flags: Int) {
            // Write descriptors Mat
            val matData = ByteArray(descriptors.total().toInt() * descriptors.elemSize().toInt())
            if(descriptors.rows() > 0 && descriptors.cols() > 0) {
                descriptors.get(0, 0, matData)
            }
            parcel.writeInt(descriptors.rows())
            parcel.writeInt(descriptors.cols())
            parcel.writeInt(descriptors.type())
            parcel.writeInt(matData.size)
            parcel.writeByteArray(matData)

            // Write world points
            parcel.writeList(worldPoints)
        }
    }
}