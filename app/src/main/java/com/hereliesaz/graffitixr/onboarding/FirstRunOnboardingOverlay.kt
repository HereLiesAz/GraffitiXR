package com.hereliesaz.graffitixr.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * First-run AR coaching layer, drawn as a full-screen sibling over the GL camera surface. Staged:
 *
 *  1. "Doodle this on your wall or canvas" appears centred, alone, for [TITLE_HOLD_MS].
 *  2. The title animates up to the top and the generated [scribble] fades in below it — this is the
 *     reference the user copies onto their real wall.
 *  3. Once ARCore is ready ([isArReady]) but no surface has been found yet ([planeDetected] false),
 *     movement guidance ([movementHint]) + a rotating hint icon appear below the scribble. They
 *     disappear the moment a surface is found, so the user isn't nagged once tracking is working.
 *
 * This layer shows the scribble + coaching only; latching the overlay onto the drawn marks and the
 * swap to the user's artwork are the next phase. The caller is responsible for only composing this
 * during the first-run flow (no other modal open).
 */
@Composable
fun FirstRunOnboardingOverlay(
    scribble: Scribble,
    isArReady: Boolean,
    planeDetected: Boolean,
    title: String,
    movementHint: String,
    modifier: Modifier = Modifier,
) {
    var titleAtTop by remember { mutableStateOf(false) }
    LaunchedEffectHold { titleAtTop = true }

    // -1 = top, 0 = centre. The title starts centred, then rises to make room for the scribble.
    val titleBias by animateFloatAsState(
        targetValue = if (titleAtTop) -0.86f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "onboardingTitleBias",
    )

    Box(modifier.fillMaxSize()) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(BiasAlignment(0f, titleBias))
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 24.dp),
        )

        // The scribble reference occupies the centre once the title has cleared out.
        AnimatedVisibility(
            visible = titleAtTop,
            enter = fadeIn(tween(500)),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            ScribbleView(
                scribble = scribble,
                modifier = Modifier.size(240.dp),
            )
        }

        // Movement guidance: only while ARCore is up but hasn't found a surface yet.
        AnimatedVisibility(
            visible = titleAtTop && isArReady && !planeDetected,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(BiasAlignment(0f, 0.72f)),
        ) {
            MovementGuidance(movementHint)
        }
    }
}

@Composable
private fun MovementGuidance(hint: String) {
    val transition = rememberInfiniteTransition(label = "moveHint")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "moveHintAngle",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp).rotate(angle),
        )
        Text(
            text = hint,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Holds for [TITLE_HOLD_MS] then invokes [onElapsed] once. Extracted so the delay reads clearly. */
@Composable
private fun LaunchedEffectHold(onElapsed: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        delay(TITLE_HOLD_MS)
        onElapsed()
    }
}

private const val TITLE_HOLD_MS = 1000L
