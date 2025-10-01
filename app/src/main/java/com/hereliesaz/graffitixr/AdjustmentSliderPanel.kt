package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A composable that displays a single, integrated slider panel at the bottom of the screen.
 *
 * This panel is designed to be less intrusive than a full-screen dialog. It shows a slider
 * corresponding to the given [SliderType] and allows for real-time adjustment of image properties.
 *
 * @param modifier A [Modifier] for this composable.
 * @param sliderType The [SliderType] to display a slider for.
 * @param uiState The current [UiState] of the application, used to bind the slider's value.
 * @param viewModel The [MainViewModel] to call when the slider's value changes.
 */
@Composable
fun AdjustmentSliderPanel(
    modifier: Modifier = Modifier,
    sliderType: SliderType,
    uiState: UiState,
    viewModel: MainViewModel,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = sliderType.name, style = MaterialTheme.typography.bodyLarge)
            when (sliderType) {
                SliderType.Opacity -> Slider(
                    value = uiState.opacity,
                    onValueChange = viewModel::onOpacityChange
                )
                SliderType.Contrast -> Slider(
                    value = uiState.contrast,
                    onValueChange = viewModel::onContrastChange,
                    valueRange = 0f..10f
                )
                SliderType.Saturation -> Slider(
                    value = uiState.saturation,
                    onValueChange = viewModel::onSaturationChange,
                    valueRange = 0f..10f
                )
                SliderType.Brightness -> Slider(
                    value = uiState.brightness,
                    onValueChange = viewModel::onBrightnessChange,
                    valueRange = -1f..1f
                )
            }
        }
    }
}