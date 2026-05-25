package com.hereliesaz.graffitixr.onboarding

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.random.Random

@Composable
fun ModeOnboardingOverlay(
    onboarding: ModeOnboarding,
    onDismiss: () -> Unit,
) = ModeOnboardingOverlay(stepKey = onboarding.mode, lines = onboarding.lines, onDismiss = onDismiss)

/**
 * Generic tap-to-advance onboarding overlay over arbitrary [lines]. [stepKey] resets the
 * step counter when it changes (so a new rail-item context starts from line 0) and seeds
 * the popup zone. Used both for per-mode onboarding and for rail-tap tutorial text.
 */
@Composable
fun ModeOnboardingOverlay(
    stepKey: Any,
    lines: List<String>,
    onDismiss: () -> Unit,
) {
    var step by rememberSaveable(stepKey) { mutableStateOf(0) }
    val line = lines.getOrNull(step)
    if (line == null) {
        onDismiss()
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(stepKey) {
                detectTapGestures {
                    val next = step + 1
                    if (next >= lines.size) onDismiss() else step = next
                }
            }
    ) {
        // Pick one of nine in-bounds zones based on a (key, step) seed so the
        // text moves between popups but never the same zone twice in a row.
        val seed = stepKey.hashCode()
        val zone = remember(stepKey, step) {
            zoneForSeed(seed * 1000 + step, prevStep = step - 1, prevMode = seed)
        }

        Box(
            modifier = Modifier
                .align(zone)
                // Reserve the bottom strip so a bottom-zone line never lands behind the editor's
                // undo / redo / magic-wand FABs. Top stays at 48dp; bottom clears the FAB row.
                .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 140.dp)
                .widthIn(max = maxWidth * 0.75f)
        ) {
            // Drop-shadow halo so plain text reads on any background.
            Text(
                text = line,
                color = Color.Black.copy(alpha = 0.85f),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(start = 1.dp, top = 1.dp)
            )
            Text(
                text = line,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Start,
            )
        }
    }
}

private val zones: List<Alignment> = listOf(
    Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd,
    Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd,
    Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd,
)

private fun zoneForSeed(seed: Int, prevStep: Int, prevMode: Int): Alignment {
    val rng = Random(seed)
    val pick = zones[rng.nextInt(zones.size)]
    if (prevStep < 0) return pick
    val prev = zones[Random(prevMode * 1000 + prevStep).nextInt(zones.size)]
    // Avoid landing in the same zone two popups in a row — rotate to the next
    // distinct zone so successive lines never overlap each other.
    return if (pick == prev) zones[(zones.indexOf(pick) + 1) % zones.size] else pick
}
