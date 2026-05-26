package com.hereliesaz.graffitixr.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun ModeOnboardingOverlay(
    onboarding: ModeOnboarding,
    onDismiss: () -> Unit,
) {
    // First-run per-mode onboarding manages its own step locally (no rail-tap driving).
    var step by rememberSaveable(onboarding.mode) { mutableStateOf(0) }
    ModeOnboardingOverlay(
        positionKey = onboarding.mode,
        step = step,
        lines = onboarding.lines,
        onAdvance = { step++ },
        onDismiss = onDismiss,
    )
}

/**
 * Generic onboarding overlay over arbitrary [lines]. The current [step] is controlled by the
 * caller so any tap source — the non-consuming screen observer below, the per-step timer, or an
 * external rail tap — can drive advancement through the same [onAdvance]. [positionKey] seeds the
 * popup zone and resets the tap observer when the context changes.
 *
 * The overlay never consumes pointer events, so taps fall through to the canvas/editor underneath
 * and the walkthrough never blocks interaction.
 */
@Composable
fun ModeOnboardingOverlay(
    positionKey: Any,
    step: Int,
    lines: List<String>,
    onAdvance: () -> Unit,
    onDismiss: () -> Unit,
) {
    val line = lines.getOrNull(step)
    LaunchedEffect(line) {
        if (line == null) {
            onDismiss()
        }
    }

    if (line == null) {
        return
    }

    // Keep advance current without restarting the pointer loop on every step.
    val advance by rememberUpdatedState(onAdvance)

    // Per-step timer: longer lines linger longer. Restarts whenever the step (from any
    // source) or context changes; firing past the last line trips the dismiss guard above.
    LaunchedEffect(positionKey, step) {
        delay(stepDurationMs(line))
        advance()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(positionKey) {
                awaitPointerEventScope {
                    while (true) {
                        // Observe in the Initial pass and never consume, so the tap still
                        // reaches whatever is underneath while also advancing the step.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.fastAny { it.changedToDownIgnoreConsumed() }) {
                            advance()
                        }
                    }
                }
            }
    ) {
        // Pick one of nine in-bounds zones based on a (key, step) seed so the
        // text moves between popups but never the same zone twice in a row.
        val seed = positionKey.hashCode()
        val zone = remember(positionKey, step) {
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

private fun stepDurationMs(line: String): Long =
    (3000L + 50L * line.length).coerceIn(3000L, 10000L)

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
