package com.hereliesaz.graffitixr.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.TapFeedback

@Composable
fun TapFeedbackEffect(feedback: TapFeedback?) {
    feedback ?: return

    val isSuccess = feedback is TapFeedback.Success
    val color = if (isSuccess) Color.Green else Color.Red
    val position = when(feedback) {
        is TapFeedback.Success -> feedback.position
        is TapFeedback.Failure -> feedback.position
    }

    val radius = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(feedback) {
        // Reset and start animations
        radius.snapTo(0f)
        alpha.snapTo(1f)

        radius.animateTo(
            targetValue = 100f,
            animationSpec = tween(durationMillis = 400)
        )
    }

    LaunchedEffect(feedback) {
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 400, delayMillis = 100)
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = color.copy(alpha = alpha.value),
            radius = radius.value,
            center = position,
            style = Stroke(width = 4.dp.toPx())
        )
    }
}