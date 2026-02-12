package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.feature.editor.ui.GestureFeedback

@Composable
fun EditorUi(
    actions: EditorViewModel,
    uiState: EditorUiState,
    isTouchLocked: Boolean,
    showUnlockInstructions: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {

        if (uiState.gestureInProgress || uiState.showRotationAxisFeedback) {
            GestureFeedback(
                uiState = uiState,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
            )
        }

        if (uiState.activePanel != EditorPanel.NONE) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                when (uiState.activePanel) {
                    EditorPanel.LAYERS -> LayersPanel(
                        layers = uiState.layers,
                        activeLayerId = uiState.activeLayerId,
                        onSelectLayer = actions::onLayerActivated,
                        onToggleVisibility = { },
                        onClose = { actions.onAdjustClicked() } // Close action
                    )
                    EditorPanel.ADJUST -> AdjustPanel(onDismiss = { })
                    EditorPanel.COLOR -> ColorPanel(onDismiss = { })
                    EditorPanel.BLEND -> BlendPanel(onDismiss = { })
                    else -> {}
                }
            }
        }
    }
}

// --- Internal Panels ---

@Composable
fun LayersPanel(
    layers: List<Layer>,
    activeLayerId: String?,
    onSelectLayer: (String) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Layers", style = MaterialTheme.typography.titleMedium, color = Color.White)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(layers.reversed()) { layer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLayer(layer.id) }
                        .background(if (layer.id == activeLayerId) Color.Gray else Color.Transparent)
                        .padding(8.dp)
                ) {
                    Text(layer.name, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AdjustPanel(onDismiss: () -> Unit) {
    Column {
        Text("Adjustments", color = Color.White)
        Slider(value = 0.5f, onValueChange = {})
    }
}

@Composable
fun ColorPanel(onDismiss: () -> Unit) {
    Column {
        Text("Color Balance", color = Color.White)
        Slider(value = 0.5f, onValueChange = {})
    }
}

@Composable
fun BlendPanel(onDismiss: () -> Unit) {
    Column {
        Text("Blend Mode", color = Color.White)
    }
}