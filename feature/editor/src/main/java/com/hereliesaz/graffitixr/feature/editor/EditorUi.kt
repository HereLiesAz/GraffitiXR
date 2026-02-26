package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.design.components.AdjustmentsPanel
import com.hereliesaz.graffitixr.design.components.AdjustmentsState
import com.hereliesaz.graffitixr.feature.editor.ui.GestureFeedback

@Composable
fun EditorUi(
    actions: EditorViewModel,
    uiState: EditorUiState,
    isTouchLocked: Boolean,
    showUnlockInstructions: Boolean,
    isCapturingTarget: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {

        GestureFeedback(
            state = uiState,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Layer List Panel (Conditional)
            if (uiState.activePanel == EditorPanel.LAYERS) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    LayersPanel(
                        layers = uiState.layers,
                        activeLayerId = uiState.activeLayerId,
                        onSelectLayer = actions::onLayerActivated,
                        onToggleVisibility = { /* Toggle logic */ },
                        onClose = { actions.onDismissPanel() }
                    )
                }
            }

            // 2. Integrated Adjustments Panel (Knobs + Undo/Redo/Magic)
            val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
            val overlayLayer = activeLayer?.let {
                OverlayLayer(
                    id = it.id,
                    name = it.name,
                    uri = it.uri ?: android.net.Uri.EMPTY,
                    opacity = it.opacity,
                    brightness = it.brightness,
                    contrast = it.contrast,
                    saturation = it.saturation,
                    colorBalanceR = it.colorBalanceR,
                    colorBalanceG = it.colorBalanceG,
                    colorBalanceB = it.colorBalanceB,
                    isImageLocked = it.isImageLocked
                )
            }

            AdjustmentsPanel(
                state = AdjustmentsState(
                    hideUiForCapture = uiState.hideUiForCapture,
                    isTouchLocked = isTouchLocked,
                    hasImage = uiState.layers.isNotEmpty(),
                    isArMode = uiState.editorMode == EditorMode.AR,
                    hasHistory = uiState.undoCount > 0 || uiState.redoCount > 0,
                    undoCount = uiState.undoCount,
                    redoCount = uiState.redoCount,
                    isRightHanded = uiState.isRightHanded,
                    isCapturingTarget = isCapturingTarget,
                    activeLayer = overlayLayer
                ),
                showKnobs = uiState.activePanel == EditorPanel.ADJUST,
                showColorBalance = uiState.activePanel == EditorPanel.COLOR,
                isLandscape = isLandscape,
                screenHeight = screenHeight,
                onOpacityChange = actions::onOpacityChanged,
                onBrightnessChange = actions::onBrightnessChanged,
                onContrastChange = actions::onContrastChanged,
                onSaturationChange = actions::onSaturationChanged,
                onColorBalanceRChange = actions::onColorBalanceRChanged,
                onColorBalanceGChange = actions::onColorBalanceGChanged,
                onColorBalanceBChange = actions::onColorBalanceBChanged,
                onUndo = actions::onUndoClicked,
                onRedo = actions::onRedoClicked,
                onMagicAlign = actions::onMagicClicked,
                onAdjustmentStart = actions::onAdjustmentStart,
                onAdjustmentEnd = actions::onAdjustmentEnd
            )
        }
    }
}

@Composable
fun LayersPanel(
    layers: List<Layer>,
    activeLayerId: String?,
    onSelectLayer: (String) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Layers", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                "Close",
                color = Color.Gray,
                modifier = Modifier.clickable { onClose() }.padding(8.dp)
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(layers.reversed()) { layer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectLayer(layer.id) }
                        .background(if (layer.id == activeLayerId) Color.Gray.copy(alpha = 0.3f) else Color.Transparent)
                        .padding(8.dp)
                ) {
                    Text(layer.name, color = Color.White)
                }
            }
        }
    }
}