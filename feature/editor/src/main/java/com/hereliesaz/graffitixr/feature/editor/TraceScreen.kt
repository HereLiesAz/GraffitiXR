
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.data.OnboardingManager
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.design.components.OnboardingDialog

@Composable
fun TraceScreen(viewModel: EditorViewModel, isLibraryVisible: Boolean) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val onboardingManager = remember(context) { OnboardingManager(context) }
    var showOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(isLibraryVisible, uiState.editorMode) {
        // Only show if library is hidden AND we are actually in TRACE mode in the ViewModel
        if (!isLibraryVisible && uiState.editorMode == EditorMode.TRACE) {
            if (onboardingManager.isFirstTime(EditorMode.TRACE.name)) {
                showOnboarding = true
                onboardingManager.markAsSeen(EditorMode.TRACE.name)
            }
        }
    }

    if (showOnboarding) {
        OnboardingDialog(
            mode = EditorMode.TRACE,
            onDismiss = { showOnboarding = false }
        )
    }

    TraceControls(
        onTransformStart = viewModel::onGestureStart,
        onTransform = viewModel::onTransformGesture,
        onTransformEnd = viewModel::onGestureEnd
    )
}

@Composable
fun TraceControls(
    onTransformStart: () -> Unit,
    onTransform: (Offset, Float, Float) -> Unit,
    onTransformEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onTransformStart()
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    onTransformEnd()
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    onTransform(pan, zoom, rotation)
                }
            }
    )
}
