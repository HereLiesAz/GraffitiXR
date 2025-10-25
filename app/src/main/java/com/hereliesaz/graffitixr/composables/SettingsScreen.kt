package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: UiState,
    onOpacityChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onColorBalanceRChanged: (Float) -> Unit,
    onColorBalanceGChanged: (Float) -> Unit,
    onColorBalanceBChanged: (Float) -> Unit,
    onCurvesPointsChanged: (List<Offset>) -> Unit,
    onCurvesPointsChangeFinished: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("General", "Curves")

    Scaffold(
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            when (selectedTab) {
                0 -> GeneralSettings(
                    uiState = uiState,
                    onOpacityChanged = onOpacityChanged,
                    onSaturationChanged = onSaturationChanged,
                    onContrastChanged = onContrastChanged,
                    onColorBalanceRChanged = onColorBalanceRChanged,
                    onColorBalanceGChanged = onColorBalanceGChanged,
                    onColorBalanceBChanged = onColorBalanceBChanged
                )
                1 -> CurvesAdjustment(
                    points = uiState.curvesPoints,
                    onPointsChanged = onCurvesPointsChanged,
                    onDragEnd = onCurvesPointsChangeFinished,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun GeneralSettings(
    uiState: UiState,
    onOpacityChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onColorBalanceRChanged: (Float) -> Unit,
    onColorBalanceGChanged: (Float) -> Unit,
    onColorBalanceBChanged: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Opacity")
        Slider(value = uiState.opacity, onValueChange = onOpacityChanged)
        Text("Saturation")
        Slider(value = uiState.saturation, onValueChange = onSaturationChanged)
        Text("Contrast")
        Slider(value = uiState.contrast, onValueChange = onContrastChanged)
        Text("Red")
        Slider(value = uiState.colorBalanceR, onValueChange = onColorBalanceRChanged)
        Text("Green")
        Slider(value = uiState.colorBalanceG, onValueChange = onColorBalanceGChanged)
        Text("Blue")
        Slider(value = uiState.colorBalanceB, onValueChange = onColorBalanceBChanged)
    }
}
