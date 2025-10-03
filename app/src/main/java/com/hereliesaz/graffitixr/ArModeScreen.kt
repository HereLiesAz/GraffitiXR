package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ar.core.Session

/**
 * A composable screen that hosts the Augmented Reality experience.
 *
 * This screen contains the `ArView` for rendering the scene and passes the
 * necessary state and event handlers to it.
 *
 * @param uiState The current UI state of the application.
 * @param onSessionInitialized A callback for when the AR session is initialized.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
fun ArModeScreen(
    uiState: UiState,
    onSessionInitialized: (Session) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        ArView(
            uiState = uiState,
            onSessionInitialized = onSessionInitialized
        )
    }
}