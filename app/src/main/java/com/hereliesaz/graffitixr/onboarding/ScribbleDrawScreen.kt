package com.hereliesaz.graffitixr.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape

/**
 * First-run step one: show the scribble and wait while the user copies it onto their wall.
 *
 * Deliberately **not** an AR view. Nothing here needs the camera, a tracked plane or an anchor —
 * the user is looking at their phone and drawing on a wall, so the scribble gets the whole screen
 * at maximum legibility instead of competing with a live camera feed and a pose that isn't
 * established yet. Detection happens afterwards, as its own step, once there is actually something
 * drawn to detect.
 *
 * The scribble fills a square that tracks the screen width, which is what makes it big enough to
 * copy at arm's length.
 */
@Composable
fun ScribbleDrawScreen(
    scribble: Scribble,
    title: String,
    hint: String,
    doneLabel: String,
    onDrawn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            // Opaque: this is a step in its own right, not a translucent coaching layer, and a solid
            // ground keeps the marks maximally readable.
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            ScribbleView(
                scribble = scribble,
                modifier = Modifier
                    .fillMaxWidth()
                    // Square, so the generator's normalized [0,1]x[0,1] layout is drawn without
                    // distorting the glyph spread.
                    .aspectRatio(1f)
                    .weight(1f, fill = false),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = hint,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                AzButton(text = doneLabel, onClick = onDrawn, shape = AzButtonShape.RECTANGLE)
            }
        }
    }
}
