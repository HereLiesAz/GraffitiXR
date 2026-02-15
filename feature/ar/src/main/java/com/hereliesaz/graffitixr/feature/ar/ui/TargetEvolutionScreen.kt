package com.hereliesaz.graffitixr.feature.ar.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.graffitixr.feature.ar.EvolutionMode
import com.hereliesaz.graffitixr.feature.ar.TargetCreationViewModel

@Composable
fun TargetEvolutionScreen(
    image: Bitmap,
    onCornersConfirmed: (List<Offset>) -> Unit,
    viewModel: TargetCreationViewModel = hiltViewModel()
) {
    LaunchedEffect(image) {
        viewModel.setImage(image)
    }

    val state by viewModel.uiState.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. The Reality (Original Image)
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "Reality",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        // 2. The Hallucination (Mask Overlay)
        state.maskImage?.let { mask ->
            Image(
                bitmap = mask.asImageBitmap(),
                contentDescription = "Mask",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            viewModel.onTouch(offset, size.width, size.height)
                        }
                    },
                contentScale = ContentScale.FillBounds,
                alpha = 0.5f,
                colorFilter = ColorFilter.tint(
                    if (state.mode == EvolutionMode.ADD) Color.Green else Color.Red,
                    BlendMode.SrcIn
                )
            )
        }

        // 4. The Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                FilterChip(
                    selected = state.mode == EvolutionMode.ADD,
                    onClick = { viewModel.setMode(EvolutionMode.ADD) },
                    label = { Text("Assimilate") },
                    leadingIcon = { Icon(Icons.Default.Add, null) }
                )

                Spacer(modifier = Modifier.width(16.dp))

                FilterChip(
                    selected = state.mode == EvolutionMode.SUBTRACT,
                    onClick = { viewModel.setMode(EvolutionMode.SUBTRACT) },
                    label = { Text("Purge") },
                    leadingIcon = { Icon(Icons.Default.Remove, null) }
                )
            }

            Button(
                onClick = { onCornersConfirmed(state.derivedCorners) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !state.isProcessing && state.derivedCorners.isNotEmpty()
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Accept Truth")
            }
        }

        if (state.isProcessing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}