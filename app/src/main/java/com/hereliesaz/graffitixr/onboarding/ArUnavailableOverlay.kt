package com.hereliesaz.graffitixr.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One-shot explainer shown on first launch when ArCoreApk reports that ARCore
 * is not supported on this device. Tap-to-advance, dismissed automatically
 * after the last line.
 *
 * This is deliberately NOT part of the reactive rail guidance graph: it fires
 * before any rail interaction, on devices with no ARCore. It is gated only by
 * its own one-shot persistence (the `ar_unavailable_explainer` key) and MUST
 * only be composed when no other modal is open (library / settings / export /
 * capture); the caller is responsible for that gating.
 *
 * Accessibility: the whole surface is one button (role + click label, so TalkBack announces
 * "Next, button" / "Done, button" rather than a bare tap target); each line is a single text node
 * (the legibility shadow is part of its style, not a second copy that a screen reader would read
 * twice); and each new line is a polite live region so it is announced as it appears.
 *
 * @param advanceLabel Click label for every line but the last (e.g. "Next").
 * @param dismissLabel Click label for the last line, which closes the overlay (e.g. "Done").
 */
@Composable
fun ArUnavailableOverlay(
    lines: List<String>,
    advanceLabel: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val line = lines.getOrNull(step)
    if (line == null) {
        // Nothing (left) to show. Dismiss as an effect, never as a side effect of composition: a
        // composable body can run any number of times and must not call back into state.
        LaunchedEffect(Unit) { currentOnDismiss() }
        return
    }
    val isLast = step >= lines.lastIndex

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = if (isLast) dismissLabel else advanceLabel,
                role = Role.Button,
            ) {
                if (isLast) currentOnDismiss() else step += 1
            }
    ) {
        val alignment = when (step % 3) {
            0 -> Alignment.TopCenter
            1 -> Alignment.Center
            else -> Alignment.BottomCenter
        }
        Box(
            modifier = Modifier
                .align(alignment)
                .padding(horizontal = 32.dp, vertical = 48.dp)
                .widthIn(max = maxWidth * 0.85f)
        ) {
            Text(
                text = line,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        offset = Offset(2f, 2f),
                        blurRadius = 4f,
                    ),
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
