package com.hereliesaz.graffitixr.onboarding

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun ModeOnboardingOverlay(
    onboarding: ModeOnboarding,
    onDismiss: () -> Unit,
) {
    var step by rememberSaveable(onboarding.mode) { mutableStateOf(0) }
    val line = onboarding.lines.getOrNull(step)
    if (line == null) {
        onDismiss()
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onboarding.mode) {
                detectTapGestures {
                    val next = step + 1
                    if (next >= onboarding.lines.size) onDismiss() else step = next
                }
            }
    ) {
        val w = maxWidth
        val h = maxHeight
        val offset = remember(onboarding.mode, step, w, h) {
            pseudoRandomOffset(seed = onboarding.mode.ordinal * 1000 + step, w, h)
        }
        // Soft shadow halo: render the same text twice — a dark, blurred-feel
        // copy slightly offset behind, and the white copy on top — so plain
        // text reads on any background without a card/dialog chrome.
        Text(
            text = line,
            color = Color.Black.copy(alpha = 0.85f),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Start,
            modifier = Modifier
                .offset(x = offset.x + 1.dp, y = offset.y + 1.dp)
                .padding(24.dp)
                .widthIn(max = w * 0.8f)
        )
        Text(
            text = line,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Start,
            modifier = Modifier
                .offset(x = offset.x, y = offset.y)
                .padding(24.dp)
                .widthIn(max = w * 0.8f)
        )
    }
}

private fun pseudoRandomOffset(seed: Int, maxW: Dp, maxH: Dp): DpOffset {
    val rng = Random(seed)
    // bound text into a 70% × 60% interior box (anchored at top-left of the
    // 24dp padding), leaving room for the rail and avoiding the screen edges.
    val x = (rng.nextFloat() * 0.7f) * maxW.value
    val y = (0.1f + rng.nextFloat() * 0.6f) * maxH.value
    return DpOffset(x.dp, y.dp)
}
