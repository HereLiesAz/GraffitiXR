package com.hereliesaz.graffitixr

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
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.UiState
import com.hereliesaz.graffitixr.feature.ar.TargetRefinementScreen
import com.hereliesaz.graffitixr.feature.ar.UnwarpScreen
import com.hereliesaz.graffitixr.feature.ar.TargetCreationOverlay

@Composable
fun TargetCreationFlow(uiState: UiState, viewModel: MainViewModel, context: Context) {
    Box(Modifier.fillMaxSize()) {
        if (uiState.captureStep == CaptureStep.REVIEW) {
            val uri = uiState.capturedTargetUris.firstOrNull()
            val imageBitmap by produceState<Bitmap?>(null, uri) { 
                uri?.let { 
                    value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it)) 
                    else @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri) 
                } 
            }

            val maskBitmap by produceState<Bitmap?>(null, uiState.targetMaskUri) { 
                val targetMaskUri = uiState.targetMaskUri
                value = if (targetMaskUri != null) withContext(Dispatchers.IO) { 
                    ImageUtils.loadBitmapFromUri(context, targetMaskUri) 
                } else null 
            }

            TargetRefinementScreen(
                targetImage = imageBitmap,
                mask = maskBitmap,
                keypoints = uiState.detectedKeypoints,
                paths = uiState.refinementPaths,
                isEraser = uiState.isRefinementEraser,
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                onPathAdded = viewModel::onRefinementPathAdded,
                onModeChanged = { viewModel.onRefinementModeChanged(it) }, // it is Boolean (isEraser)
                onUndo = viewModel::onUndoClicked,
                onRedo = viewModel::onRedoClicked,
                onConfirm = { viewModel.onConfirmTargetCreation() }
            )
        } else if (uiState.captureStep == CaptureStep.RECTIFY) {
             val uri = uiState.capturedTargetUris.firstOrNull()
            val imageBitmap by produceState<Bitmap?>(null, uri, uiState.capturedTargetImages) { 
                value = if (uiState.capturedTargetImages.isNotEmpty()) 
                    uiState.capturedTargetImages.first() 
                else if (uri != null) { 
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) 
                    else @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri) 
                } else null 
            }
            UnwarpScreen(uiState.isRightHanded, imageBitmap, viewModel::unwarpImage, viewModel::onRetakeCapture)
        } else {
            TargetCreationOverlay(
                isRightHanded = uiState.isRightHanded,
                step = uiState.captureStep,
                targetCreationMode = uiState.targetCreationMode,
                gridRows = uiState.gridRows,
                gridCols = uiState.gridCols,
                qualityWarning = uiState.qualityWarning,
                captureFailureTimestamp = uiState.captureFailureTimestamp,
                onCaptureClick = {
                    if (uiState.captureStep.name.startsWith("CALIBRATION_POINT")) {
                        viewModel.onCalibrationPointCaptured(FloatArray(16))
                    } else {
                        viewModel.onCaptureShutterClicked()
                    }
                },
                onCancelClick = viewModel::onCancelCaptureClicked,
                onMethodSelected = viewModel::onTargetCreationMethodSelected,
                onGridConfigChanged = viewModel::onGridConfigChanged,
                onGpsDecision = viewModel::onGpsDecision,
                onFinishPhotoSequence = viewModel::onPhotoSequenceFinished
            )
        }
    }
}
