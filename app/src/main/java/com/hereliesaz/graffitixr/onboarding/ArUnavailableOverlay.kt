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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One-shot explainer shown on first launch when ArCoreApk reports that ARCore
 * is not supported on this device. Tap-to-advance, dismissed automatically
 * after the last line. Visually matches [ModeOnboardingOverlay].
 *
 * MUST only be composed when no other modal is open (library / settings /
 * export / capture). Caller is responsible for that gating, mirroring the
 * pattern used for [ModeOnboardingOverlay].
 */
@Composable
fun ArUnavailableOverlay(
    lines: List<String>,
    onDismiss: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    val line = lines.getOrNull(step)
    if (line == null) {
        onDismiss()
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    val next = step + 1
                    if (next >= lines.size) onDismiss() else step = next
                }
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
                color = Color.Black.copy(alpha = 0.85f),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 1.dp, top = 1.dp)
            )
            Text(
                text = line,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
            )
        }
    }
}
