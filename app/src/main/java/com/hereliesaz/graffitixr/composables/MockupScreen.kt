package com.hereliesaz.graffitixr.composables

import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * A composable screen for creating a mock-up of a mural on a static background image.
 *
 * This screen allows the user to select a background and an overlay image. The overlay image can
 * be freely transformed using perspective warp handles, allowing for precise placement and
 * realistic mock-ups. The image properties (opacity, contrast, saturation) are controlled
 * via the main navigation rail.
 *
 * @param uiState The current UI state, containing the URIs for the images and their properties.
 * @param onBackgroundImageSelected A callback to be invoked when the user selects a new background image.
 * @param onOverlayImageSelected A callback to be invoked when the user selects a new overlay image.
 * @param onOpacityChanged A callback to be invoked when the opacity of the overlay is changed.
 * @param onContrastChanged A callback to be invoked when the contrast of the overlay is changed.
 * @param onSaturationChanged A callback to be invoked when the saturation of the overlay is changed.
 * @param onPointsInitialized A callback to initialize the positions of the warp handles.
 * @param onPointChanged A callback to be invoked when a warp handle is dragged.
 * @param isWarpEnabled A flag to enable or disable the perspective warping feature.
 */
@Composable
fun MockupScreen(
    uiState: UiState,
    onBackgroundImageSelected: (Uri) -> Unit,
    onOverlayImageSelected: (Uri) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onRotationZChanged: (Float) -> Unit,
    onRotationXChanged: (Float) -> Unit,
    onRotationYChanged: (Float) -> Unit,
    onOffsetChanged: (Offset) -> Unit,
    onCycleRotationAxis: () -> Unit,
    onGestureInProgress: (Boolean) -> Unit
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

    val colorMatrix = remember(uiState.saturation, uiState.contrast, uiState.colorBalanceR, uiState.colorBalanceG, uiState.colorBalanceB) {
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
            val colorBalanceMatrix = ColorMatrix(
                floatArrayOf(
                    uiState.colorBalanceR, 0f, 0f, 0f, 0f,
                    0f, uiState.colorBalanceG, 0f, 0f, 0f,
                    0f, 0f, uiState.colorBalanceB, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            timesAssign(contrastMatrix)
            timesAssign(colorBalanceMatrix)
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
                    val result =
                        (context.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    imageBitmap = result
                }
            }

            val transformState = rememberTransformableState { zoomChange, offsetChange, rotationChange ->
                onScaleChanged(zoomChange)
                onOffsetChanged(offsetChange)
                when (uiState.activeRotationAxis) {
                    com.hereliesaz.graffitixr.RotationAxis.X -> onRotationXChanged(rotationChange)
                    com.hereliesaz.graffitixr.RotationAxis.Y -> onRotationYChanged(rotationChange)
                    com.hereliesaz.graffitixr.RotationAxis.Z -> onRotationZChanged(rotationChange)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onGestureInProgress(true) },
                            onDragEnd = { onGestureInProgress(false) }
                        ) { _, _ -> }
                    }
            ) {
                imageBitmap?.let { bmp ->
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = uiState.scale
                                scaleY = uiState.scale
                                rotationX = uiState.rotationX
                                rotationY = uiState.rotationY
                                rotationZ = uiState.rotationZ
                                translationX = uiState.offset.x
                                translationY = uiState.offset.y
                            }
                            .transformable(state = transformState)
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { onCycleRotationAxis() })
                            }
                    ) {
                        onGestureInProgress(transformState.isTransformInProgress)
                        drawImage(
                            image = bmp.asImageBitmap(),
                            alpha = uiState.opacity,
                            colorFilter = ColorFilter.colorMatrix(colorMatrix),
                            blendMode = uiState.blendMode
                        )
                    }
                }
            }
        }

    }
}