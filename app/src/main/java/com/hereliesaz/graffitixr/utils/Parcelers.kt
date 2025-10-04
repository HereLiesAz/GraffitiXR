package com.hereliesaz.graffitixr.utils

import android.os.Parcel
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.graphics.ArFeaturePattern
import kotlinx.parcelize.Parceler
import org.opencv.core.CvType
import org.opencv.core.Mat

object OffsetParceler : Parceler<Offset> {
    override fun create(parcel: Parcel): Offset {
        return Offset(parcel.readFloat(), parcel.readFloat())
    }

    override fun Offset.write(parcel: Parcel, flags: Int) {
        parcel.writeFloat(x)
        parcel.writeFloat(y)
    }
}

object MatParceler : Parceler<Mat> {
    override fun create(parcel: Parcel): Mat {
        val rows = parcel.readInt()
        val cols = parcel.readInt()
        val type = parcel.readInt()
        val data = ByteArray(parcel.readInt())
        parcel.readByteArray(data)
        val mat = Mat(rows, cols, type)
        mat.put(0, 0, data)
        return mat
    }

    override fun Mat.write(parcel: Parcel, flags: Int) {
        parcel.writeInt(rows())
        parcel.writeInt(cols())
        parcel.writeInt(type())
        val data = ByteArray(total().toInt() * elemSize().toInt())
        get(0, 0, data)
        parcel.writeInt(data.size)
        parcel.writeByteArray(data)
    }
}

object ArFeaturePatternParceler : Parceler<ArFeaturePattern?> {
    override fun create(parcel: Parcel): ArFeaturePattern? {
        val hasPattern = parcel.readByte() == 1.toByte()
        return if (hasPattern) {
            val descriptors = MatParceler.create(parcel)
            val worldPointsSize = parcel.readInt()
            val worldPoints = List(worldPointsSize) {
                parcel.createFloatArray()!!
            }
            ArFeaturePattern(descriptors, worldPoints)
        } else {
            null
        }
    }

    override fun ArFeaturePattern?.write(parcel: Parcel, flags: Int) {
        if (this == null) {
            parcel.writeByte(0)
        } else {
            parcel.writeByte(1)
            with(MatParceler) {
                descriptors.write(parcel, flags)
            }
            parcel.writeInt(worldPoints.size)
            worldPoints.forEach { parcel.writeFloatArray(it) }
        }
    }
}

object PoseParceler : Parceler<Pose?> {
    override fun create(parcel: Parcel): Pose? {
        val hasPose = parcel.readByte() == 1.toByte()
        return if (hasPose) {
            val translation = parcel.createFloatArray()
            val rotation = parcel.createFloatArray()
            Pose(translation, rotation)
        } else {
            null
        }
    }

    override fun Pose?.write(parcel: Parcel, flags: Int) {
        if (this == null) {
            parcel.writeByte(0)
        } else {
            parcel.writeByte(1)
            parcel.writeFloatArray(translation)
            parcel.writeFloatArray(rotationQuaternion)
        }
    }
}