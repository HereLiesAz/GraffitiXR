// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilScreen.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Stencil Mode screen.
 *
 * Phase A: Stub — confirms navigation wiring is correct and the composable destination
 * resolves without crashing. Full UI implemented in Phase C.
 *
 * Architecture:
 *  - Hosted by AzNavHost under EditorMode.STENCIL.name
 *  - Rail items defined in MainActivity alongside the other mode rail sub-items
 *  - StencilViewModel (Phase C) drives all state via StencilUiState
 */
@Composable
fun StencilScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Stencil Mode\n(Phase A stub)",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
