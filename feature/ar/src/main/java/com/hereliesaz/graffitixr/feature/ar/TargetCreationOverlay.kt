package com.hereliesaz.graffitixr.ui.components

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

@Composable
fun TargetCreationOverlay(
    onConfirm: (List<PointF>) -> Unit,
    onCancel: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Default corners (normalized 0.0 - 1.0)
    var topLeft by remember { mutableStateOf(Offset(0.2f, 0.2f)) }
    var topRight by remember { mutableStateOf(Offset(0.8f, 0.2f)) }
    var bottomRight by remember { mutableStateOf(Offset(0.8f, 0.8f)) }
    var bottomLeft by remember { mutableStateOf(Offset(0.2f, 0.8f)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val pos = change.position

                        val touchRadius = 100f // Hit test radius in pixels

                        fun toPixel(norm: Offset) = Offset(norm.x * w, norm.y * h)
                        fun dist(p1: Offset, p2: Offset) = hypot(p1.x - p2.x, p1.y - p2.y)

                        // Check which corner is closest and move it
                        when {
                            dist(pos, toPixel(topLeft)) < touchRadius ->
                                topLeft += Offset(dragAmount.x / w, dragAmount.y / h)
                            dist(pos, toPixel(topRight)) < touchRadius ->
                                topRight += Offset(dragAmount.x / w, dragAmount.y / h)
                            dist(pos, toPixel(bottomRight)) < touchRadius ->
                                bottomRight += Offset(dragAmount.x / w, dragAmount.y / h)
                            dist(pos, toPixel(bottomLeft)) < touchRadius ->
                                bottomLeft += Offset(dragAmount.x / w, dragAmount.y / h)
                        }
                    }
                }
        ) {
            val w = size.width.toFloat()
            val h = size.height.toFloat()

            val p1 = Offset(topLeft.x * w, topLeft.y * h)
            val p2 = Offset(topRight.x * w, topRight.y * h)
            val p3 = Offset(bottomRight.x * w, bottomRight.y * h)
            val p4 = Offset(bottomLeft.x * w, bottomLeft.y * h)

            // Draw the Quad
            val path = Path().apply {
                moveTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                lineTo(p3.x, p3.y)
                lineTo(p4.x, p4.y)
                close()
            }

            drawPath(
                path = path,
                color = Color.Green,
                style = Stroke(width = 5f)
            )

            // Draw Handles
            drawCircle(Color.Red, radius = 20f, center = p1)
            drawCircle(Color.Red, radius = 20f, center = p2)
            drawCircle(Color.Red, radius = 20f, center = p3)
            drawCircle(Color.Red, radius = 20f, center = p4)
        }

        // Action Buttons
        Button(
            onClick = {
                // Convert to Android PointF for backend processing
                val points = listOf(
                    PointF(topLeft.x, topLeft.y),
                    PointF(topRight.x, topRight.y),
                    PointF(bottomRight.x, bottomRight.y),
                    PointF(bottomLeft.x, bottomLeft.y)
                )
                onConfirm(points)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Text("RECTIFY")
        }

        Button(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text("CANCEL")
        }
    }
}