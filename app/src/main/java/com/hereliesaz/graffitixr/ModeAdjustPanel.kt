package com.hereliesaz.graffitixr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.ModeAdjustment

/**
 * Compact panel for editing a mode's whole-design tone (the transform — move/scale/rotate — is
 * driven by direct gestures on the canvas while the mode Layer is selected). Reads and writes the
 * [ModeAdjustment] for [mode]; changes persist per mode.
 */
@Composable
fun ModeAdjustPanel(
    mode: EditorMode,
    adjustment: ModeAdjustment,
    onChange: (ModeAdjustment) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .background(Color(0xEE1A1A1A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${mode.name} · Layer", color = Color.Cyan, fontWeight = FontWeight.Bold)

        AdjustSlider("Brightness", adjustment.brightness, -1f, 1f) { onChange(adjustment.copy(brightness = it)) }
        AdjustSlider("Contrast", adjustment.contrast, 0f, 2f) { onChange(adjustment.copy(contrast = it)) }
        AdjustSlider("Saturation", adjustment.saturation, 0f, 2f) { onChange(adjustment.copy(saturation = it)) }
        AdjustSlider("Opacity", adjustment.opacity, 0f, 1f) { onChange(adjustment.copy(opacity = it)) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Invert", color = Color.White)
            Switch(checked = adjustment.isInverted, onCheckedChange = { onChange(adjustment.copy(isInverted = it)) })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AzButton(text = "Reset", onClick = onReset, shape = AzButtonShape.RECTANGLE)
            AzButton(text = "Done", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color.White)
            Text("%.2f".format(value), color = Color.LightGray)
        }
        Slider(value = value.coerceIn(min, max), onValueChange = onValueChange, valueRange = min..max)
    }
}
