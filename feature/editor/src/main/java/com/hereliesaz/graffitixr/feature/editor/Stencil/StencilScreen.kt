package com.hereliesaz.graffitixr.feature.editor.stencil

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.StencilLayer
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilLayerType
import com.hereliesaz.graffitixr.common.model.StencilUiState
import com.hereliesaz.graffitixr.design.theme.DarkGrey
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme

// Inversion matrix: Inverts RGB channels, keeps Alpha.
// Stencils are Black-on-White, so this makes them White-on-Black.
private val InvertMatrix = ColorMatrix(floatArrayOf(
    -1f,  0f,  0f,  0f, 255f,
     0f, -1f,  0f,  0f, 255f,
     0f,  0f, -1f,  0f, 255f,
     0f,  0f,  0f,  1f,   0f
))

@Composable
fun StencilScreen(viewModel: StencilViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    StencilScreenContent(uiState)
}

@Composable
fun StencilScreenContent(uiState: StencilUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkGrey),
        contentAlignment = Alignment.Center
    ) {
        if (uiState.isProcessing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(uiState.processingStage, color = Color.White)
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
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.colorMatrix(InvertMatrix)
                )
                
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(32.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${activeLayer.label} (${uiState.layerCount.count} Layers) - Pages: ${uiState.totalPageCount}",
                        color = Color.White,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        } else {
            Text("No stencil layers generated.", color = Color.White)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun StencilScreenPreview() {
    val mockBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.WHITE)
        val canvas = android.graphics.Canvas(this)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 5f
        }
        canvas.drawLine(10f, 10f, 90f, 90f, paint)
    }

    val mockState = StencilUiState(
        stencilLayers = listOf(
            StencilLayer(StencilLayerType.SILHOUETTE, mockBitmap)
        ),
        activeStencilLayerIndex = 0,
        layerCount = StencilLayerCount.ONE,
        totalPageCount = 1
    )

    GraffitiXRTheme {
        StencilScreenContent(mockState)
    }
}
