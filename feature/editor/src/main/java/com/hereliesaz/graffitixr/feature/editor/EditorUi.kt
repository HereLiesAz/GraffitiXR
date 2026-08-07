// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorUi.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.design.components.AdjustmentsPanel
import com.hereliesaz.graffitixr.design.components.AdjustmentsState
import com.hereliesaz.graffitixr.feature.editor.ui.GestureFeedback
import com.hereliesaz.graffitixr.design.theme.AppStrings

@Composable
fun EditorUi(
    actions: EditorViewModel,
    uiState: EditorUiState,
    isTouchLocked: Boolean,
    showUnlockInstructions: Boolean,
    strings: AppStrings,
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
                        onToggleVisibility = actions::onToggleVisibility,
                        onRename = actions::onLayerRenamed,
                        onRemove = actions::onLayerRemoved,
                        onReorder = actions::onLayerReordered,
                        onClose = { actions.onDismissPanel() },
                        strings = strings
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
                    isImageLocked = it.isImageLocked,
                    isInverted = it.isInverted
                )
            }

            // Modes (anything but the Design screen) show the finished design with off-rail adjustment
            // knobs; undo/redo sit beneath those knobs here too, so every mode keeps history controls.
            val inMode = uiState.editorMode != EditorMode.DESIGN
            // In a Mode the adjust knobs drive the whole-design mode adjustment (which always exists),
            // not the active layer — so they work without a selected layer and tone the whole design.
            val modeAdj = if (inMode) uiState.modeAdjustments[uiState.editorMode] ?: ModeAdjustment() else null
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
                    activeLayer = overlayLayer,
                    showUndoRedo = true
                ),
                // In a Mode the adjust knobs are the off-rail tools (opacity/saturation/…); in Design
                // they're rail-triggered via the Adjust panel. Hidden while capturing a target so they
                // don't overlap the target-creation dialog.
                showKnobs = !isCapturingTarget &&
                    (uiState.activePanel == EditorPanel.ADJUST || (inMode && uiState.layers.isNotEmpty())),
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
                onAdjustmentStart = actions::onAdjustmentStart,
                onAdjustmentEnd = actions::onAdjustmentEnd,
                strings = strings,
                modeOpacity = modeAdj?.opacity,
                modeBrightness = modeAdj?.brightness,
                modeContrast = modeAdj?.contrast,
                modeSaturation = modeAdj?.saturation,
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
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onClose: () -> Unit,
    strings: AppStrings
) {
    // Every control here drives an EditorViewModel action that already existed, was already
    // covered by tests, and had no way to be invoked: a layer could be added but never removed,
    // renamed, reordered, or hidden — onToggleVisibility was even passed into this panel and then
    // never attached to anything.
    var renaming by remember { mutableStateOf<Layer?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.editor.layers, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                strings.common.close,
                color = Color.Gray,
                modifier = Modifier.clickable { onClose() }.padding(8.dp)
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            // Topmost layer first on screen, which is the reverse of the draw order the model
            // stores. The index maths below converts back before reordering.
            itemsIndexed(layers.reversed()) { displayIndex, layer ->
                val modelIndex = layers.lastIndex - displayIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (layer.id == activeLayerId) Color.Gray.copy(alpha = 0.3f) else Color.Transparent)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = layer.name,
                        color = if (layer.isVisible) Color.White else Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectLayer(layer.id) }
                            .padding(8.dp),
                    )
                    LayerAction(
                        label = if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer,
                        enabled = true,
                    ) { onToggleVisibility(layer.id) }
                    LayerAction(label = "\u2191", enabled = modelIndex < layers.lastIndex) {
                        onReorder(layers.map { it.id }.swapped(modelIndex, modelIndex + 1))
                    }
                    LayerAction(label = "\u2193", enabled = modelIndex > 0) {
                        onReorder(layers.map { it.id }.swapped(modelIndex, modelIndex - 1))
                    }
                    LayerAction(label = strings.editor.renameHint, enabled = true) { renaming = layer }
                    LayerAction(label = strings.editor.delete, enabled = true) { onRemove(layer.id) }
                }
            }
        }
    }

    renaming?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(strings.editor.renameHint) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                Text(
                    text = strings.common.done,
                    modifier = Modifier
                        .clickable {
                            // A blank name would render as an unclickable empty row.
                            if (draft.isNotBlank()) onRename(target.id, draft.trim())
                            renaming = null
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = strings.common.cancel,
                    modifier = Modifier.clickable { renaming = null }.padding(8.dp),
                )
            },
        )
    }
}

/** Swap two indices, returning a new list. Out-of-range indices return the list unchanged. */
private fun List<String>.swapped(a: Int, b: Int): List<String> {
    if (a !in indices || b !in indices || a == b) return this
    val out = toMutableList()
    out[a] = this[b]
    out[b] = this[a]
    return out
}

@Composable
private fun LayerAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}
