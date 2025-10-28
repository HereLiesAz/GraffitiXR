package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun DrawingCanvas(
    paths: List<List<Pair<Float, Float>>>,
    onPathFinished: (List<Pair<Float, Float>>) -> Unit
) {
    val currentPath = remember { mutableStateOf<List<Pair<Float, Float>>?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        currentPath.value = listOf(Pair(offset.x, offset.y))
                    },
                    onDrag = { change, _ ->
                        val newPoint = Pair(change.position.x, change.position.y)
                        currentPath.value = currentPath.value?.plus(newPoint)
                    },
                    onDragEnd = {
                        currentPath.value?.let { onPathFinished(it) }
                        currentPath.value = null
                    }
                )
            }
    ) {
        paths.forEach { points ->
            val path = Path()
            if (points.isNotEmpty()) {
                path.moveTo(points[0].first, points[0].second)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].first, points[i].second)
                }
            }
            drawPath(
                path = path,
                color = Color.Red,
                style = Stroke(width = 5f)
            )
        }
        currentPath.value?.let { points ->
            val path = Path()
            if (points.isNotEmpty()) {
                path.moveTo(points[0].first, points[0].second)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].first, points[i].second)
                }
            }
            drawPath(
                path = path,
                color = Color.Red,
                style = Stroke(width = 5f)
            )
        }
    }
}
