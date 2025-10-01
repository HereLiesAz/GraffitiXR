// TODO: This file is unused and should be removed. The state is handled by MainViewModel and UiState.
package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
class ImageSettingsState {
    var opacity by mutableStateOf(1f)
    var contrast by mutableStateOf(1f)
    var saturation by mutableStateOf(1f)
    var brightness by mutableStateOf(0f)
    var imageUri by mutableStateOf<Uri?>(null)
    var activeSlider by mutableStateOf<SliderType?>(null)
}

@Composable
fun rememberImageSettingsState(): ImageSettingsState {
    return remember { ImageSettingsState() }
}
