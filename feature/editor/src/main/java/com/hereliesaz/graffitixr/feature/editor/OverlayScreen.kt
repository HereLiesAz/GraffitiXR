package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.data.OnboardingManager
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.design.components.OnboardingDialog

@Composable
fun OverlayScreen(viewModel: EditorViewModel, isLibraryVisible: Boolean) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val onboardingManager = remember(context) { OnboardingManager(context) }
    var showOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(isLibraryVisible, uiState.editorMode) {
        if (!isLibraryVisible && uiState.editorMode == EditorMode.OVERLAY) {
            if (onboardingManager.isFirstTime(EditorMode.OVERLAY.name)) {
                showOnboarding = true
                onboardingManager.markAsSeen(EditorMode.OVERLAY.name)
            }
        }
    }

    if (showOnboarding) {
        OnboardingDialog(
            mode = EditorMode.OVERLAY,
            onDismiss = { showOnboarding = false }
        )
    }

    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    if (activeLayer != null && activeLayer.isVisible) {
        activeLayer.bitmap?.let { b ->
            // Create ColorMatrix for image adjustments
            val colorMatrix = remember(
                activeLayer.saturation,
                activeLayer.contrast,
                activeLayer.brightness,
                activeLayer.colorBalanceR,
                activeLayer.colorBalanceG,
                activeLayer.colorBalanceB
            ) {
                createColorMatrix(
                    saturation = activeLayer.saturation,
                    contrast = activeLayer.contrast,
                    brightness = activeLayer.brightness,
                    colorBalanceR = activeLayer.colorBalanceR,
                    colorBalanceG = activeLayer.colorBalanceG,
                    colorBalanceB = activeLayer.colorBalanceB
                )
            }

            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                alpha = activeLayer.opacity,
                colorFilter = ColorFilter.colorMatrix(colorMatrix)
                // BlendMode handled via GraphicsLayer or Canvas
            )
        }
    }
}
