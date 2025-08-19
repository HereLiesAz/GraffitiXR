package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.Painter
import androidx.xr.compose.spatial.SpatialImage
import com.google.ar.core.Pose
import kotlin.math.abs

@Composable
fun Mural(
    markers: List<Pose>,
    painter: Painter,
    opacity: Float,
    contrast: Float,
    saturation: Float
) {
    if (markers.size == 4) {
        val centerPose = calculateCenterPose(markers)
        val width = calculateWidth(markers)
        val height = calculateHeight(markers)

        SpatialImage(
            painter = painter,
            contentDescription = "Mural",
            initialPose = centerPose,
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            width = width,
            height = height,
            alpha = opacity,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                ColorMatrix().apply {
                    setToSaturation(saturation)
                    postConcat(
                        ColorMatrix(floatArrayOf(
                        contrast, 0f, 0f, 0f, 0f,
                        0f, contrast, 0f, 0f, 0f,
                        0f, 0f, contrast, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    )
                }
            )
        )
    }
}

fun calculateCenterPose(markers: List<Pose>): Pose {
    val x = markers.map { it.tx() }.average().toFloat()
    val y = markers.map { it.ty() }.average().toFloat()
    val z = markers.map { it.tz() }.average().toFloat()
    val qx = markers.map { it.qx() }.average().toFloat()
    val qy = markers.map { it.qy() }.average().toFloat()
    val qz = markers.map { it.qz() }.average().toFloat()
    val qw = markers.map { it.qw() }.average().toFloat()
    return Pose(floatArrayOf(x, y, z), floatArrayOf(qx, qy, qz, qw))
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
