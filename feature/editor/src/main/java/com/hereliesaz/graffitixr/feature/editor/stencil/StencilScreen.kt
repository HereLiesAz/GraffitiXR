package com.hereliesaz.graffitixr.feature.editor.stencil

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.StencilLayer
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilLayerType
import com.hereliesaz.graffitixr.common.model.StencilUiState
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.DarkGrey
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme

@Composable
fun StencilScreen(viewModel: StencilViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    StencilScreenContent(
        uiState = uiState,
        onLayerSelected = { viewModel.setActiveStencilLayer(it) }
    )

    // Trigger share sheet when PDF is ready
    LaunchedEffect(uiState.exportedPdfUri) {
        uiState.exportedPdfUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Stencil PDF"))
            viewModel.clearExportState()
        }
    }
}

@Composable
fun StencilScreenContent(
    uiState: StencilUiState,
    onLayerSelected: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Transparent),
        contentAlignment = Alignment.Center
    ) {
        // ── Main Preview ─────────────────────────────────────────────────────
        if (uiState.stencilLayers.isNotEmpty()) {
            val activeLayer = uiState.stencilLayers.getOrNull(uiState.activeStencilLayerIndex)
            if (activeLayer != null) {
                // Background for the stencil sheet (emulating paper)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Image(
                        bitmap = activeLayer.bitmap.asImageBitmap(),
                        contentDescription = activeLayer.label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // ── Processing State ─────────────────────────────────────────────────
        if (uiState.isProcessing || uiState.isExporting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (uiState.isExporting) "Generating PDF…" else uiState.processingStage,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    if (!uiState.isExporting) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { uiState.processingProgress },
                            modifier = Modifier.width(200.dp),
                            color = Cyan,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                    }
                }
            }
        }

        // ── Layer Indicator Strip ────────────────────────────────────────────
        if (uiState.stencilLayers.isNotEmpty() && !uiState.isProcessing) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    uiState.stencilLayers.forEachIndexed { index, layer ->
                        val isSelected = index == uiState.activeStencilLayerIndex
                        LayerThumbnail(
                            layer = layer,
                            isSelected = isSelected,
                            onClick = { onLayerSelected(index) }
                        )
                    }
                }
            }
        }

        // ── Error Feedback ───────────────────────────────────────────────────
        uiState.exportError?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 100.dp)
                    .padding(horizontal = 32.dp),
                color = Color(0xFFD32F2F),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = error,
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun LayerThumbnail(
    layer: StencilLayer,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) Cyan else Color.DarkGray,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(4.dp)
        ) {
            Image(
                bitmap = layer.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = layer.type.label.take(3).uppercase(),
            color = if (isSelected) Cyan else Color.White,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Preview(showBackground = true)
@Composable
fun StencilScreenPreview() {
    val mockBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.WHITE)
        val canvas = Canvas(this)
        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 5f
        }
        canvas.drawLine(10f, 10f, 90f, 90f, paint)
    }

    val mockState = StencilUiState(
        stencilLayers = listOf(
            StencilLayer(StencilLayerType.SILHOUETTE, mockBitmap),
            StencilLayer(StencilLayerType.HIGHLIGHT, mockBitmap)
        ),
        activeStencilLayerIndex = 0,
        layerCount = StencilLayerCount.TWO,
        totalPageCount = 4
    )

    GraffitiXRTheme {
        StencilScreenContent(mockState, {})
    }
}
