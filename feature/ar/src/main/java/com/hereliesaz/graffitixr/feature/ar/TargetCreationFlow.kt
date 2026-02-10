package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

@Composable
fun TargetCreationFlow(
    uiState: ArUiState,
    isRightHanded: Boolean,
    captureStep: CaptureStep,
    context: Context,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onCaptureShutter: () -> Unit,
    onCalibrationPointCaptured: (FloatArray) -> Unit,
    onUnwarpImage: (List<Offset>) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        when (captureStep) {
            CaptureStep.RECTIFY -> {
                UnwarpScreen(
                    isRightHanded = isRightHanded,
                    targetImage = uiState.tempCaptureBitmap,
                    onConfirm = onUnwarpImage,
                    onRetake = onRetake
                )
            }
            CaptureStep.REVIEW -> {
                val uri = uiState.capturedTargetUris.firstOrNull()
                val imageBitmap by produceState<Bitmap?>(null, uri) {
                    uri?.let {
                        value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it))
                        else @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, it)
                    }
                }

                TargetRefinementScreen(
                    bitmap = imageBitmap,
                    onConfirm = onConfirm,
                    onRetake = onRetake
                )
            }
            else -> {
                TargetCreationOverlay(
                    uiState = uiState,
                    onCapture = onCaptureShutter,
                    onConfirm = onConfirm,
                    onRetake = onRetake,
                    onCancel = onCancel
                )
            }
        }
    }
}
