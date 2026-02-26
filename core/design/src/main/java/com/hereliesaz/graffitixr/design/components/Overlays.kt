package com.hereliesaz.graffitixr.design.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.graffitixr.common.model.ArState
import kotlinx.coroutines.delay

@Composable
fun TouchLockOverlay(isLocked: Boolean, onUnlockRequested: () -> Unit) {
    if (!isLocked) return
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(Color.Transparent)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var tapCount = 0
                    var lastTapTime = 0L
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull()
                        if (change != null && change.changedToUp()) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 500) tapCount++ else tapCount = 1
                            lastTapTime = now
                            if (tapCount == 4) {
                                onUnlockRequested()
                                tapCount = 0
                            }
                        }
                        awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                    }
                }
            }
    )
}

@Composable
fun StatusOverlay(qualityWarning: String?, arState: ArState, isPlanesDetected: Boolean, isTargetCreated: Boolean, modifier: Modifier) {
    AnimatedVisibility(true, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val bg = if (qualityWarning != null) Color.Red.copy(0.8f) else Color.Black.copy(0.5f)
        val txt = when {
            qualityWarning != null -> qualityWarning
            !isTargetCreated -> "Create a Grid to start."
            arState == ArState.SEARCHING && !isPlanesDetected -> "Scan surfaces around you."
            arState == ArState.SEARCHING && isPlanesDetected -> "Tap a surface to place anchor."
            arState == ArState.LOCKED -> "Looking for your Grid..."
            arState == ArState.PLACED -> "Ready."
            else -> ""
        }
        if (txt.isNotEmpty()) {
            Box(Modifier.background(bg, RoundedCornerShape(8.dp)).padding(16.dp, 8.dp)) {
                Text(txt, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun CaptureAnimation() {
    var f by remember { mutableFloatStateOf(0f) }
    var s by remember { mutableFloatStateOf(0f) }
    val af by animateFloatAsState(f, tween(200))
    val `as` by animateFloatAsState(s, tween(300))

    LaunchedEffect(Unit) {
        s = 0.5f
        delay(100)
        f = 1f
        delay(50)
        f = 0f
        delay(150)
        s = 0f
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = `as`)).zIndex(10f))
    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = af)).zIndex(11f))
}

@Composable
fun UnlockInstructionsPopup(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }, modifier = Modifier.fillMaxSize().zIndex(200f)) {
        Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.background(Color.Black.copy(0.8f), RoundedCornerShape(16.dp)).padding(24.dp, 16.dp)) {
                Text("Press Volume Up & Down to unlock", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}
