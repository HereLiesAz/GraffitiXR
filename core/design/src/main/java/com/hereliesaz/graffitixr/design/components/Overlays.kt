package com.hereliesaz.graffitixr.design.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.graffitixr.design.R

@Composable
fun TouchLockOverlay(isLocked: Boolean) {
    if (!isLocked) return
    // Unlock is no longer a touch gesture on this surface — a resting hand or paper being traced
    // over it could brush a tap sequence by accident, the exact thing the lock exists to prevent.
    // It now takes a deliberate press on the physical volume buttons instead (see
    // MainActivity.dispatchKeyEvent), so this overlay's only job left is blocking every touch.
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(Color.Transparent)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                    }
                }
            }
    )
}

@Composable
fun UnlockInstructionsPopup(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }, modifier = Modifier.fillMaxSize().zIndex(200f)) {
        Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.background(Color.Black.copy(0.8f), RoundedCornerShape(16.dp)).padding(24.dp, 16.dp)) {
                Text(stringResource(R.string.unlock_hint), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}
