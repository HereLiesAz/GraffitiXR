package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.os.Build
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.hereliesaz.graffitixr.ArView
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.RotationAxis
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.graphics.ArRenderer
import com.hereliesaz.graffitixr.graphics.ProjectionUtils
import kotlin.math.roundToInt

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ArModeScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    val permissions = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
    }
    val permissionStates = rememberMultiplePermissionsState(permissions = permissions)

    LaunchedEffect(Unit) {
        if (!permissionStates.allPermissionsGranted) {
            permissionStates.launchMultiplePermissionRequest()
        }
    }

    if (permissionStates.allPermissionsGranted) {
        ArContent(uiState = uiState, viewModel = viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val revokedPermissions = permissionStates.revokedPermissions.joinToString { it.permission }
            Text("Permissions required for AR mode: $revokedPermissions. Please grant them in settings.")
        }
    }
}

@Composable
private fun ArContent(
    uiState: UiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    var arRenderer by remember { mutableStateOf<ArRenderer?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier
        .fillMaxSize()
        .onSizeChanged { viewSize = it }) {
        ArView(
            modifier = Modifier.fillMaxSize(),
            onArImagePlaced = viewModel::onArImagePlaced,
            onPlanesDetected = viewModel::onPlanesDetected,
            getRenderer = { arRenderer = it }
        )

        // Only show the image if an anchor has been placed.
        val imageUri = uiState.backgroundRemovedImageUri ?: uiState.overlayImageUri
        if (imageUri != null && uiState.arImagePose != null) {
            // Project the 3D anchor pose to 2D screen coordinates.
            val projectedOffset = arRenderer?.let {
                ProjectionUtils.projectWorldToScreen(
                    pose = uiState.arImagePose,
                    viewMatrix = it.viewMatrix,
                    projectionMatrix = it.projectionMatrix,
                    viewWidth = viewSize.width,
                    viewHeight = viewSize.height
                )
            }

            if (projectedOffset != null) {
                val transformState = rememberTransformableState { zoomChange, offsetChange, rotationChange ->
                    viewModel.onScaleChanged(zoomChange)
                    viewModel.onOffsetChanged(offsetChange)
                    when (uiState.activeRotationAxis) {
                        RotationAxis.X -> viewModel.onRotationXChanged(rotationChange)
                        RotationAxis.Y -> viewModel.onRotationYChanged(rotationChange)
                        RotationAxis.Z -> viewModel.onRotationZChanged(rotationChange)
                    }
                }

                AsyncImage(
                    model = imageUri,
                    contentDescription = "Overlay Image",
                    modifier = Modifier
                        // Center the image on the projected anchor point.
                        .offset {
                            IntOffset(
                                projectedOffset.x.roundToInt() - viewSize.width / 2,
                                projectedOffset.y.roundToInt() - viewSize.height / 2
                            )
                        }
                        // Apply user transformations.
                        .graphicsLayer(
                            scaleX = uiState.scale,
                            scaleY = uiState.scale,
                            translationX = uiState.offset.x,
                            translationY = uiState.offset.y,
                            rotationX = uiState.rotationX,
                            rotationY = uiState.rotationY,
                            rotationZ = uiState.rotationZ,
                            alpha = uiState.opacity
                        )
                        .transformable(state = transformState)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { viewModel.onCycleRotationAxis() })
                        },
                    colorFilter = ColorFilter.colorMatrix(
                        ColorMatrix().apply {
                            setToSaturation(uiState.saturation)
                            val contrastMatrix = ColorMatrix(
                                floatArrayOf(
                                    uiState.contrast, 0f, 0f, 0f, (1 - uiState.contrast) * 128,
                                    0f, uiState.contrast, 0f, 0f, (1 - uiState.contrast) * 128,
                                    0f, 0f, uiState.contrast, 0f, (1 - uiState.contrast) * 128,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            this *= contrastMatrix
                        }
                    )
                )
            }
        }

        if (!uiState.arePlanesDetected && uiState.arState == com.hereliesaz.graffitixr.ArState.SEARCHING) {
            Text(
                text = "Move your device to find a surface.",
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}