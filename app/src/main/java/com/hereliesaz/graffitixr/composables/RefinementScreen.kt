package com.hereliesaz.graffitixr.composables

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun RefinementScreen(
    imageUri: Uri,
    onConfirm: (Rect) -> Unit,
    onCancel: () -> Unit
) {
    var cropRect by remember { mutableStateOf(Rect(100f, 100f, 300f, 300f)) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = "Image for refinement",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        Canvas(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    cropRect = cropRect.translate(dragAmount)
                }
            }) {
            drawRect(
                color = Color.White,
                topLeft = cropRect.topLeft,
                size = cropRect.size,
                style = Stroke(width = 2f)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            Button(onClick = { onConfirm(cropRect) }) {
                Text("Confirm")
            }
            Button(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}
