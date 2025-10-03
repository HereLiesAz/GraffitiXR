package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest

/**
 * A composable screen for mocking up a mural on a static background image.
 *
 * @param uiState The current UI state of the application.
 * @param onPointsChanged A callback invoked when the user moves one of the warp points.
 * @param isWarpEnabled A boolean indicating if the warp handles should be active.
 */
@Composable
fun MockupScreen(
    uiState: UiState,
    onPointsChanged: (List<Offset>) -> Unit,
    isWarpEnabled: Boolean
) {
    val context = LocalContext.current
    var overlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var containerSize by remember { mutableStateOf<IntSize?>(null) }

    LaunchedEffect(uiState.overlayImageUri) {
        uiState.overlayImageUri?.let {
            val request = ImageRequest.Builder(context).data(it).build()
            overlayBitmap = (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        uiState.backgroundImageUri?.let {
            AsyncImage(
                model = it,
                contentDescription = "Background Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        val bmp = overlayBitmap
        val size = containerSize
        if (bmp != null && size != null) {
            val points = uiState.mockupPoints.ifEmpty {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val bmpWidth = bmp.width.toFloat() / 2f
                val bmpHeight = bmp.height.toFloat() / 2f
                listOf(
                    Offset(centerX - bmpWidth, centerY - bmpHeight),
                    Offset(centerX + bmpWidth, centerY - bmpHeight),
                    Offset(centerX + bmpWidth, centerY + bmpHeight),
                    Offset(centerX - bmpWidth, centerY + bmpHeight)
                ).also { onPointsChanged(it) } // Initialize points
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    val meshWidth = 20
                    val meshHeight = 20
                    val verts = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)

                    val p0 = points[0]
                    val p1 = points[1]
                    val p2 = points[2]
                    val p3 = points[3]

                    for (j in 0..meshHeight) {
                        val vj = j.toFloat() / meshHeight
                        for (i in 0..meshWidth) {
                            val ui = i.toFloat() / meshWidth
                            val x = (1 - vj) * ((1 - ui) * p0.x + ui * p1.x) + vj * ((1 - ui) * p3.x + ui * p2.x)
                            val y = (1 - vj) * ((1 - ui) * p0.y + ui * p1.y) + vj * ((1 - ui) * p3.y + ui * p2.y)
                            val index = (j * (meshWidth + 1) + i) * 2
                            verts[index] = x
                            verts[index + 1] = y
                        }
                    }

                    val colorMatrix = ColorMatrix().apply {
                        setToSaturation(uiState.saturation)
                        val contrastValue = uiState.contrast
                        val contrastMat = ColorMatrix(
                            floatArrayOf(
                                contrastValue, 0f, 0f, 0f, (1 - contrastValue) * 128,
                                0f, contrastValue, 0f, 0f, (1 - contrastValue) * 128,
                                0f, 0f, contrastValue, 0f, (1 - contrastValue) * 128,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        this *= contrastMat
                    }

                    val paint = Paint().apply {
                        alpha = (uiState.opacity * 255).toInt()
                        colorFilter = android.graphics.ColorFilter.colorMatrix(colorMatrix.values)
                    }

                    canvas.nativeCanvas.drawBitmapMesh(bmp, meshWidth, meshHeight, verts, 0, null, 0, paint)
                }
            }

            if (isWarpEnabled) {
                points.forEachIndexed { index, offset ->
                    Box(
                        modifier = Modifier
                            .offset((offset.x - 12).dp, (offset.y - 12).dp)
                            .size(24.dp)
                            .background(Color.White.copy(alpha = 0.5f), CircleShape)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val newPoints = points.toMutableList()
                                    newPoints[index] = points[index] + dragAmount
                                    onPointsChanged(newPoints)
                                }
                            }
                    )
                }
            }
        }
    }
}