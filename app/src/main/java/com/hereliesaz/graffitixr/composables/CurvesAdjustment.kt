package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun CurvesAdjustment(
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val scaledPoints = points.map { Offset(it.x * size.width, it.y * size.height) }
                        val closestPoint = scaledPoints.minByOrNull { (it - startOffset).getDistance() }
                        val closestPointIndex = scaledPoints.indexOf(closestPoint)
                        if (closestPoint != null && (closestPoint - startOffset).getDistance() < 20.dp.toPx()) {
                            selectedPointIndex = closestPointIndex
                        }
                    },
                    onDrag = { _, dragAmount ->
                        selectedPointIndex?.let { index ->
                            val newPoints = points.toMutableList()
                            val newPoint = points[index] + Offset(dragAmount.x / size.width, dragAmount.y / size.height)
                            newPoints[index] = newPoint.copy(
                                x = newPoint.x.coerceIn(0f, 1f),
                                y = newPoint.y.coerceIn(0f, 1f)
                            )
                            onPointsChanged(newPoints)
                        }
                    },
                    onDragEnd = {
                        selectedPointIndex = null
                        onDragEnd()
                    }
                )
            }) {
            val scaledPoints = points.map { Offset(it.x * size.width, it.y * size.height) }
            val path = Path()
            path.moveTo(scaledPoints.first().x, scaledPoints.first().y)
            for (i in 0 until scaledPoints.size - 1) {
                val controlPoint1 = Offset(
                    (scaledPoints[i].x + scaledPoints[i + 1].x) / 2,
                    scaledPoints[i].y
                )
                val controlPoint2 = Offset(
                    (scaledPoints[i].x + scaledPoints[i + 1].x) / 2,
                    scaledPoints[i + 1].y
                )
                path.cubicTo(
                    controlPoint1.x, controlPoint1.y,
                    controlPoint2.x, controlPoint2.y,
                    scaledPoints[i + 1].x, scaledPoints[i + 1].y
                )
            }

            drawPath(
                path = path,
                color = Color.White,
                style = Stroke(width = 2.dp.toPx())
            )

            scaledPoints.forEach { point ->
                drawCircle(
                    color = Color.White,
                    radius = 8.dp.toPx(),
                    center = point
                )
            }
        }
    }
}
