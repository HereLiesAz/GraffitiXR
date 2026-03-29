package com.hereliesaz.graffitixr.feature.editor.stencil

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun StencilScreen(viewModel: StencilViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (uiState.isProcessing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.Black)
                Spacer(Modifier.height(16.dp))
                Text(uiState.processingStage, color = Color.Black)
            }
        } else if (uiState.exportError != null) {
            Text("Error: ${uiState.exportError}", color = Color.Red)
        } else if (uiState.stencilLayers.isNotEmpty()) {
            val activeLayer = uiState.stencilLayers.getOrNull(uiState.activeStencilLayerIndex)
            if (activeLayer != null) {
                Image(
                    bitmap = activeLayer.bitmap.asImageBitmap(),
                    contentDescription = activeLayer.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                
                Text(
                    text = "${activeLayer.label} (${uiState.layerCount.count} Layers) - Pages: ${uiState.totalPageCount}",
                    color = Color.Black,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).background(Color.White.copy(alpha=0.8f)).padding(8.dp)
                )
            }
        } else {
            Text("No stencil layers generated.", color = Color.Black)
        }
    }
}
