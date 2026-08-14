package com.hereliesaz.graffitixr.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.design.R

@Composable
private fun RotationAxis.label(): String = when (this) {
    RotationAxis.X -> stringResource(R.string.rotation_axis_x)
    RotationAxis.Y -> stringResource(R.string.rotation_axis_y)
    RotationAxis.Z -> stringResource(R.string.rotation_axis_z)
}

@Composable
fun RotationAxisFeedback(
    axis: RotationAxis,
    visible: Boolean,
    onFeedbackShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(visible) {
        if (visible) {
            onFeedbackShown()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 500, delayMillis = 500)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.rotation_axis_feedback, axis.label()),
                color = Color.White,
                modifier = Modifier.shadow(elevation = 2.dp)
            )
        }
    }
}
