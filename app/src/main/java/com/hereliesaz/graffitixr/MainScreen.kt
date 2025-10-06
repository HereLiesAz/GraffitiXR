package com.hereliesaz.graffitixr

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.graffitixr.composables.ArModeScreen
import com.hereliesaz.graffitixr.composables.ImageTraceScreen
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.composables.RotationAxisFeedback
import com.hereliesaz.graffitixr.composables.TitleOverlay
import com.hereliesaz.graffitixr.dialogs.AdjustmentSliderDialog
import com.hereliesaz.graffitixr.dialogs.OnboardingDialog
import com.hereliesaz.graffitixr.utils.captureWindow

/**
 * The main screen of the application.
 *
 * This composable acts as the primary container for the UI. It observes the [UiState] from the
 * [MainViewModel] and displays the appropriate content based on the current [EditorMode].
 * It also houses the `AzNavRail` for navigation and controls.
 *
 * @param viewModel The [MainViewModel] instance that holds the application's state and business logic.
 */
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSliderDialog by remember { mutableStateOf<String?>(null) }
    var showOnboardingForMode by remember { mutableStateOf<EditorMode?>(null) }

    LaunchedEffect(uiState.editorMode) {
        if (!uiState.completedOnboardingModes.contains(uiState.editorMode)) {
            showOnboardingForMode = uiState.editorMode
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.captureEvent.collect { event ->
            when (event) {
                is CaptureEvent.RequestCapture -> {
                    (context as? Activity)?.let { activity ->
                        captureWindow(activity) { bitmap ->
                            bitmap?.let {
                                viewModel.saveCapturedBitmap(it)
                            }
                        }
                    }
                }
            }
        }
    }

    val overlayImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onOverlayImageSelected(it) } }

    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onBackgroundImageSelected(it) } }

    Box(modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (uiState.editorMode) {
                EditorMode.STATIC -> MockupScreen(
                    uiState = uiState,
                    onBackgroundImageSelected = viewModel::onBackgroundImageSelected,
                    onOverlayImageSelected = viewModel::onOverlayImageSelected,
                    onOpacityChanged = viewModel::onOpacityChanged,
                    onContrastChanged = viewModel::onContrastChanged,
                    onSaturationChanged = viewModel::onSaturationChanged,
                    onScaleChanged = viewModel::onScaleChanged,
                    onOffsetChanged = viewModel::onOffsetChanged,
                    onRotationZChanged = viewModel::onRotationZChanged,
                    onRotationXChanged = viewModel::onRotationXChanged,
                    onRotationYChanged = viewModel::onRotationYChanged,
                    onCycleRotationAxis = viewModel::onCycleRotationAxis
                )
                EditorMode.NON_AR -> ImageTraceScreen(
                    uiState = uiState,
                    onScaleChanged = viewModel::onScaleChanged,
                    onOffsetChanged = viewModel::onOffsetChanged,
                    onRotationZChanged = viewModel::onRotationZChanged,
                    onRotationXChanged = viewModel::onRotationXChanged,
                    onRotationYChanged = viewModel::onRotationYChanged,
                    onCycleRotationAxis = viewModel::onCycleRotationAxis
                )
                EditorMode.AR -> ArModeScreen(viewModel = viewModel)
            }
        }

        Box(modifier = Modifier.zIndex(2f)) {
            AzNavRail {
                azMenuItem(
                    id = "ar_overlay",
                    text = "Overlay",
                    onClick = { viewModel.onEditorModeChanged(EditorMode.AR) })
                azMenuItem(
                    id = "trace_image",
                    text = "Trace",
                    onClick = { viewModel.onEditorModeChanged(EditorMode.NON_AR) })
                azMenuItem(
                    id = "mockup",
                    text = "Mockup",
                    onClick = { viewModel.onEditorModeChanged(EditorMode.STATIC) })

                azRailItem(id = "overlay", text = "Image") {
                    overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }

                if (uiState.editorMode == EditorMode.STATIC) {
                    azRailItem(id = "background", text = "Background") {
                        backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }

                if (uiState.overlayImageUri != null) {
                    azRailItem(
                        id = "remove_bg",
                        text = "Remove\n Background",
                        onClick = viewModel::onRemoveBackgroundClicked
                    )
                }

                if (uiState.editorMode == EditorMode.AR && uiState.arState == ArState.PLACED) {
                    azRailItem(id = "lock_ar", text = "Lock", onClick = viewModel::onArLockClicked)
                }

                azRailItem(id = "opacity", text = "Opacity") { showSliderDialog = "Opacity" }
                azRailItem(id = "contrast", text = "Contrast") { showSliderDialog = "Contrast" }
                azRailItem(id = "saturation", text = "Saturation") { showSliderDialog = "Saturation" }

                azRailItem(id = "save", text = "Save", onClick = viewModel::onSaveClicked)
                azRailItem(id = "help", text = "Help") {
                    //TODO: Add help dialog
                }
                azSettings(
                    isLoading = uiState.isLoading,
                    packRailButtons = true
                )
            }
        }

        when (showSliderDialog) {
            "Opacity" -> AdjustmentSliderDialog(
                title = "Opacity",
                value = uiState.opacity,
                onValueChange = viewModel::onOpacityChanged,
                onDismissRequest = { showSliderDialog = null }
            )
            "Contrast" -> AdjustmentSliderDialog(
                title = "Contrast",
                value = uiState.contrast,
                onValueChange = viewModel::onContrastChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
            "Saturation" -> AdjustmentSliderDialog(
                title = "Saturation",
                value = uiState.saturation,
                onValueChange = viewModel::onSaturationChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
        }

        showOnboardingForMode?.let { mode ->
            OnboardingDialog(
                editorMode = mode,
                onDismissRequest = {
                    viewModel.onOnboardingComplete(mode)
                    showOnboardingForMode = null
                }
            )
        }

        RotationAxisFeedback(
            axis = uiState.activeRotationAxis,
            visible = uiState.showRotationAxisFeedback,
            onFeedbackShown = viewModel::onFeedbackShown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )

        val title = when (uiState.editorMode) {
            EditorMode.AR -> "Overlay"
            EditorMode.NON_AR -> "Trace"
            EditorMode.STATIC -> "Mockup"
        }
        TitleOverlay(
            title = title,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
    }
}