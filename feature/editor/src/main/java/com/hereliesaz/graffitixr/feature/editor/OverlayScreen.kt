package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.runtime.*
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

    // REDUNDANCY REMOVED: Layers are now managed centrally by MainScreen's background block.
    // This prevents the 'fixed background' bug where the active layer was double-rendered 
    // without transforms on the onscreen layer.
}
