package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Knob(
    text: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    defaultValue: Float = valueRange.start,
    valueFormatter: (Float) -> String = { "%.2f".format(it) }
) {
    // Mapping angle: 135 (bottom left) to 405 (bottom right)
    val minAngle = 135f
    val maxAngle = 405f

    // Helper to map value to angle
    fun valueToAngle(v: Float): Float {
        val normalized = (v - valueRange.start) / (valueRange.endInclusive - valueRange.start)
        return minAngle + normalized * (maxAngle - minAngle)
    }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val updatedValue by rememberUpdatedState(value)
    val updatedOnValueChange by rememberUpdatedState(onValueChange)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = text
                stateDescription = valueFormatter(value)
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = valueRange
                )
                setProgress { targetValue ->
                    val newValue = targetValue.coerceIn(valueRange.start, valueRange.endInclusive)
                    if (newValue == value) {
                        false
                    } else {
                        updatedOnValueChange(newValue)
                        true
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        updatedOnValueChange(defaultValue)
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Sensitivity: full range in 300dp drag
                    val sensitivityPx = with(density) { 300.dp.toPx() }
                    val sensitivity = (valueRange.endInclusive - valueRange.start) / sensitivityPx

                    val newValue = (updatedValue - dragAmount.y * sensitivity).coerceIn(valueRange.start, valueRange.endInclusive)

                    // Haptic feedback when crossing default value
                    if ((updatedValue < defaultValue && newValue >= defaultValue) ||
                        (updatedValue > defaultValue && newValue <= defaultValue)) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }

                    updatedOnValueChange(newValue)
                }
            }
    ) {
        Box(
            modifier = Modifier.size(60.dp)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2 - 4.dp.toPx()

                // Track
                drawArc(
                    color = color.copy(alpha = 0.3f),
                    startAngle = minAngle,
                    sweepAngle = maxAngle - minAngle,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )

                // Active Arc
                val sweep = valueToAngle(value) - minAngle
                if (sweep > 0) {
                    drawArc(
                        color = color,
                        startAngle = minAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Indicator line
                val angleRad = Math.toRadians(valueToAngle(value).toDouble())
                val indicatorStart = Offset(
                    center.x + (radius - 8.dp.toPx()) * cos(angleRad).toFloat(),
                    center.y + (radius - 8.dp.toPx()) * sin(angleRad).toFloat()
                )
                val indicatorEnd = Offset(
                    center.x + radius * cos(angleRad).toFloat(),
                    center.y + radius * sin(angleRad).toFloat()
                )
                drawLine(
                    color = color,
                    start = indicatorStart,
                    end = indicatorEnd,
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
