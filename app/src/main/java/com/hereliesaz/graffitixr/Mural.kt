package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.Painter
import androidx.xr.compose.spatial.SpatialImage
import com.google.ar.core.Pose
import org.apache.commons.math3.linear.EigenDecomposition
import org.apache.commons.math3.linear.MatrixUtils
import kotlin.math.abs

@Composable
fun Mural(
    markers: List<Pose>,
    painter: Painter,
    opacity: Float,
    contrast: Float,
    saturation: Float,
    brightness: Float
) {
    if (markers.size == 4) {
        val averagePose = calculateAveragePose(markers)
        val width = calculateWidth(markers)
        val height = calculateHeight(markers)

        SpatialImage(
            painter = painter,
            contentDescription = "Mural",
            initialPose = averagePose,
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            width = width,
            height = height,
            alpha = opacity,
            colorFilter = getColorFilter(saturation, brightness, contrast)
        )
    }
}

fun calculateAveragePose(markers: List<Pose>): Pose {
    val tx = markers.map { it.tx() }.average().toFloat()
    val ty = markers.map { it.ty() }.average().toFloat()
    val tz = markers.map { it.tz() }.average().toFloat()

    val averageRotation = averageRotation(markers)

    return Pose(floatArrayOf(tx, ty, tz), averageRotation)
}

fun averageRotation(poses: List<Pose>): FloatArray {
    if (poses.isEmpty()) return floatArrayOf(0f, 0f, 0f, 1f) // Identity quaternion

    val M = MatrixUtils.createRealMatrix(4, 4)

    for (pose in poses) {
        val q = pose.rotationQuaternion
        // In ARCore, quaternion is [x, y, z, w]
        // Let's use [w, x, y, z] for standard representation in some math contexts
        val qVec = doubleArrayOf(q[3].toDouble(), q[0].toDouble(), q[1].toDouble(), q[2].toDouble())
        val qOuter = MatrixUtils.createRealMatrix(4, 4)
        for (i in 0..3) {
            for (j in 0..3) {
                qOuter.setEntry(i, j, qVec[i] * qVec[j])
            }
        }
        M.setSubMatrix(M.add(qOuter).data, 0, 0)
    }

    val eigenDecomposition = EigenDecomposition(M)
    val eigenvectors = (0..3).map { eigenDecomposition.getEigenvector(it) }
    val eigenvalues = eigenDecomposition.realEigenvalues

    val maxEigenvalueIndex = eigenvalues.indices.maxByOrNull { eigenvalues[it] } ?: 0
    val averageQuaternionVec = eigenvectors[maxEigenvalueIndex]

    // Convert back to ARCore's [x, y, z, w] format and FloatArray
    val w = averageQuaternionVec.getEntry(0).toFloat()
    val x = averageQuaternionVec.getEntry(1).toFloat()
    val y = averageQuaternionVec.getEntry(2).toFloat()
    val z = averageQuaternionVec.getEntry(3).toFloat()

    return floatArrayOf(x, y, z, w)
}


fun calculateWidth(markers: List<Pose>): Float {
    val minX = markers.minOf { it.tx() }
    val maxX = markers.maxOf { it.tx() }
    return abs(maxX - minX)
}

fun calculateHeight(markers: List<Pose>): Float {
    val minY = markers.minOf { it.ty() }
    val maxY = markers.maxOf { it.ty() }
    return abs(maxY - minY)
}
