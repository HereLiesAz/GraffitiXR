package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep

@Composable
fun TargetCreationOverlay(
    uiState: ArUiState,
    step: CaptureStep,
    onPrimaryAction: () -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {

        if (uiState.tempCaptureBitmap != null && step != CaptureStep.CAPTURE) {
            Image(
                bitmap = uiState.tempCaptureBitmap!!.asImageBitmap(),
                contentDescription = "Captured Target",
                modifier = Modifier.fillMaxSize().background(Color.Black)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Button(onClick = onPrimaryAction) {
                Text(
                    text = when (step) {
                        CaptureStep.CAPTURE -> "Capture"
                        CaptureStep.RECTIFY -> "Next"
                        CaptureStep.REVIEW -> "Confirm"
                        else -> "Action"
                    }
                )
            }
        }

        Button(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Text("Cancel")
        }
    }
}