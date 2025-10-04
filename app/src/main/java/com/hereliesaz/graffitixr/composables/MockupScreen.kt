package com.hereliesaz.graffitixr.composables

import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.hereliesaz.graffitixr.UiState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MockupScreen(
    uiState: UiState,
    onBackgroundImageSelected: (Uri) -> Unit,
    onOverlayImageSelected: (Uri) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onPointsInitialized: (List<Offset>) -> Unit,
    onPointChanged: (Int, Offset) -> Unit,
    isWarpEnabled: Boolean
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onBackgroundImageSelected(it) }
    }

    val overlayPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onOverlayImageSelected(it) }
    }

    val colorMatrix = remember(uiState.saturation, uiState.contrast) {
        ColorMatrix().apply {
            setToSaturation(uiState.saturation)
            val contrast = uiState.contrast
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, (1 - contrast) * 128,
                    0f, contrast, 0f, 0f, (1 - contrast) * 128,
                    0f, 0f, contrast, 0f, (1 - contrast) * 128,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            timesAssign(contrastMatrix)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        uiState.backgroundImageUri?.let {
            AsyncImage(
                model = it,
                contentDescription = "Background Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        uiState.overlayImageUri?.let { uri ->
            var imageBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

            LaunchedEffect(uri) {
                coroutineScope.launch {
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .build()
                    val result = (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    imageBitmap = result
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        if (uiState.points.isEmpty() && imageBitmap != null) {
                            val w = imageBitmap!!.width.toFloat()
                            val h = imageBitmap!!.height.toFloat()
                            val points = listOf(
                                Offset(0f, 0f),
                                Offset(w, 0f),
                                Offset(w, h),
                                Offset(0f, h)
                            )
                            onPointsInitialized(points)
                        }
                    }
            ) {
                val perspectiveMatrix = remember(uiState.points, imageBitmap) {
                    Matrix().apply {
                        imageBitmap?.let { bmp ->
                            if (uiState.points.size == 4) {
                                val w = bmp.width.toFloat()
                                val h = bmp.height.toFloat()
                                setPolyToPoly(
                                    floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0,
                                    uiState.points.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0,
                                    4
                                )
                            }
                        }
                    }
                }

                imageBitmap?.let { bmp ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        withTransform({
                            if (isWarpEnabled) {
                                val matrix = androidx.compose.ui.graphics.Matrix()
                                val values = FloatArray(9)
                                perspectiveMatrix.getValues(values)
                                matrix.values[0] = values[0]
                                matrix.values[1] = values[1]
                                matrix.values[3] = values[2]
                                matrix.values[4] = values[3]
                                matrix.values[5] = values[4]
                                matrix.values[7] = values[5]
                                matrix.values[12] = values[6]
                                matrix.values[13] = values[7]
                                matrix.values[15] = values[8]
                                transform(matrix)
                            }
                        }) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                alpha = uiState.opacity,
                                colorFilter = ColorFilter.colorMatrix(colorMatrix)
                            )
                        }
                    }
                }

                if (uiState.points.isNotEmpty() && isWarpEnabled) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        uiState.points.forEach { offset ->
                            drawCircle(color = Color.White, radius = 20f, center = offset, style = Stroke(width = 5f))
                        }
                    }

                    uiState.points.forEachIndexed { index, offset ->
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        offset.x.roundToInt(),
                                        offset.y.roundToInt()
                                    )
                                }
                                .size(40.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onPointChanged(index, offset + dragAmount)
                                    }
                                }
                        )
                    }
                }
            }
        }

    }
}