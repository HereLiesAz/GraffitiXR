package com.hereliesaz.graffitixr.utils

import androidx.compose.ui.graphics.Path
import kotlinx.serialization.Serializable

@Serializable
data class SerializablePath(
    val points: List<Pair<Float, Float>>
)

fun Path.toSerializablePath(): SerializablePath {
    // This is a placeholder as we can't directly access path points.
    // A real implementation would require a custom Path class that stores its points.
    return SerializablePath(emptyList())
}

fun SerializablePath.toPath(): Path {
    val path = Path()
    if (points.isNotEmpty()) {
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }
    }
    return path
}
