
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.data.OnboardingManager
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.design.components.OnboardingDialog

@Composable
fun MockupScreen(viewModel: EditorViewModel, isLibraryVisible: Boolean) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val onboardingManager = remember(context) { OnboardingManager(context) }
    var showOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(isLibraryVisible, uiState.editorMode) {
        if (!isLibraryVisible && uiState.editorMode == EditorMode.MOCKUP) {
            if (onboardingManager.isFirstTime(EditorMode.MOCKUP.name)) {
                showOnboarding = true
                onboardingManager.markAsSeen(EditorMode.MOCKUP.name)
            }
        }
    }

    if (showOnboarding) {
        OnboardingDialog(
            mode = EditorMode.MOCKUP,
            onDismiss = { showOnboarding = false }
        )
    }

    // GESTURE HANDLING REMOVED: Managed centrally by MainScreen's background block
    // to support consistent rotation axis switching and 'smart' snapping.
}
