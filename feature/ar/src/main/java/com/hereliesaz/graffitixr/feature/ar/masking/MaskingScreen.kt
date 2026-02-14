package com.hereliesaz.graffitixr.feature.ar.masking

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Screen that allows the user to mask the target image.
 * Supports manual brushing and MLKit-based auto-segmentation.
 */
@Composable
fun MaskingScreen(
    targetImage: Bitmap?,
    onConfirm: (Bitmap) -> Unit,
    onRetake: () -> Unit
) {
    if (targetImage == null) return

    var maskPath by remember { mutableStateOf(Path()) }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    
    // MLKit Segmenter
    val scope = rememberCoroutineScope()
    
    // Auto-Segment on load (Optional, or triggered by button)
    // For now, let's make it manual brush + "Auto Mask" button
    
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Draw Background Image
        androidx.compose.foundation.Image(
            bitmap = targetImage.asImageBitmap(),
            contentDescription = "Target",
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
        
        // 2. Draw Mask Overlay (Semi-transparent red where NOT masked? Or show mask?)
        // Let's assume user paints the "KEEP" area (Target).
        // Or painting the "MASK" area (Ignore).
        // Convention: "Select Subject" -> Paint what you want to KEEP.
        
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val p = Path().apply { moveTo(offset.x, offset.y) }
                            currentPath = p
                        },
                        onDragEnd = {
                            currentPath?.let {
                                maskPath.addPath(it)
                            }
                            currentPath = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentPath?.relativeLineTo(dragAmount.x, dragAmount.y)
                        }
                    )
                }
        ) {
            // Draw existing mask
            drawPath(
                path = maskPath,
                color = Color.Green.copy(alpha = 0.5f),
                style = Stroke(width = 50f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            
            // Draw current stroke
            currentPath?.let {
                drawPath(
                    path = it,
                    color = Color.Green.copy(alpha = 0.5f),
                    style = Stroke(width = 50f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        
        // Buttons
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            if (isProcessing) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            
            Button(
                onClick = { 
                    scope.launch {
                        isProcessing = true
                        val masked = applyAutoMask(targetImage)
                        isProcessing = false
                        if (masked != null) onConfirm(masked)
                    }
                },
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text("Auto Mask")
            }

            Button(
                onClick = {
                    // Apply manual mask
                    // For MVP, just passing original image if they skip masking
                    // Or we need to rasterize the path to a bitmap mask
                    onConfirm(targetImage) 
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text("Done (No Mask)")
            }
            
            Button(
                onClick = onRetake,
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text("Cancel")
            }
        }
    }
}

suspend fun applyAutoMask(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
    try {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        val result = segmenter.process(inputImage).await()
        return@withContext result.foregroundBitmap
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    }
}
