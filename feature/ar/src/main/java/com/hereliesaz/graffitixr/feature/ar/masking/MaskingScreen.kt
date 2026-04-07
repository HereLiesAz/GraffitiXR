// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/masking/MaskingScreen.kt
package com.hereliesaz.graffitixr.feature.ar.masking

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MaskingBackground(
    targetImage: Bitmap?,
    maskPath: Path,
    currentPath: Path?
) {
    if (targetImage == null) return

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.Image(
            bitmap = targetImage.asImageBitmap(),
            contentDescription = "Target",
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawPath(
                path = maskPath,
                color = Color.Green.copy(alpha = 0.5f),
                style = Stroke(width = 50f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            currentPath?.let {
                drawPath(
                    path = it,
                    color = Color.Green.copy(alpha = 0.5f),
                    style = Stroke(width = 50f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
fun MaskingUi(
    targetImage: Bitmap?,
    isProcessing: Boolean,
    maskPath: Path,
    currentPath: Path?,
    onPathStarted: (Offset) -> Unit,
    onPathFinished: () -> Unit,
    onPathDragged: (Offset) -> Unit,
    onConfirm: (Bitmap) -> Unit,
    onRetake: () -> Unit,
    onAutoMask: () -> Unit
) {
    if (targetImage == null) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = onPathStarted,
                    onDragEnd = onPathFinished,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onPathDragged(dragAmount)
                    }
                )
            }
    ) {
        if (isProcessing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        // Buttons
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            AzButton(
                text = "Auto Mask",
                onClick = onAutoMask,
                modifier = Modifier.align(Alignment.BottomStart),
                shape = AzButtonShape.RECTANGLE
            )

            AzButton(
                text = "Done (No Mask)",
                onClick = { onConfirm(targetImage) },
                modifier = Modifier.align(Alignment.BottomCenter),
                shape = AzButtonShape.RECTANGLE
            )

            AzButton(
                text = "Cancel",
                onClick = onRetake,
                modifier = Modifier.align(Alignment.TopStart),
                shape = AzButtonShape.RECTANGLE
            )
        }
    }
}

suspend fun applyAutoMask(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
    ImageProcessor.removeBackground(bitmap)
}