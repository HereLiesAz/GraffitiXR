// ~~~ FILE: ./feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/SketchToolsDialogs.kt ~~~
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private val COLOR_GRID = listOf(
    // Whites / grays / blacks
    Color.White, Color(0xFFE0E0E0), Color(0xFFBDBDBD), Color(0xFF9E9E9E),
    Color(0xFF757575), Color(0xFF424242), Color(0xFF212121), Color.Black,
    // Reds / pinks
    Color(0xFFFFCDD2), Color(0xFFEF9A9A), Color(0xFFE57373), Color(0xFFEF5350),
    Color(0xFFF44336), Color(0xFFE53935), Color(0xFFB71C1C), Color(0xFF880E4F),
    // Oranges / yellows
    Color(0xFFFFE0B2), Color(0xFFFFCC80), Color(0xFFFFA726), Color(0xFFFF9800),
    Color(0xFFFFF176), Color(0xFFFFEE58), Color(0xFFFFD600), Color(0xFFF57F17),
    // Greens
    Color(0xFFC8E6C9), Color(0xFF81C784), Color(0xFF4CAF50), Color(0xFF2E7D32),
    Color(0xFFB2DFDB), Color(0xFF4DB6AC), Color(0xFF00897B), Color(0xFF004D40),
    // Blues / purples
    Color(0xFFBBDEFB), Color(0xFF64B5F6), Color(0xFF1565C0), Color(0xFF0D47A1),
    Color(0xFFE1BEE7), Color(0xFFCE93D8), Color(0xFF8E24AA), Color(0xFF4A148C),
    // Browns / skin tones
    Color(0xFFD7CCC8), Color(0xFFA1887F), Color(0xFF6D4C41), Color(0xFF3E2723),
    Color(0xFFFFDDB4), Color(0xFFFFBD8A), Color(0xFFE8965C), Color(0xFFC26B3E),
)

@Composable
fun ColorPickerDialog(
    currentColor: Color,
    history: List<Color>,
    onSelectColor: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentColor) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {

                // Current color preview
                Box(
                    Modifier
                        .size(48.dp)
                        .background(selected, CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                )

                Spacer(Modifier.height(12.dp))

                // Main color grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentPadding = PaddingValues(2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(COLOR_GRID) { color ->
                        val isSelected = color == selected
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(color, CircleShape)
                                .border(
                                    if (isSelected) 2.dp else 0.5.dp,
                                    if (isSelected) Color.White else Color.Gray.copy(alpha = 0.4f),
                                    CircleShape
                                )
                                .clickable { selected = color }
                        )
                    }
                }

                // Recent colors row (if any)
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Recent", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Start))
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        history.take(8).forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color, CircleShape)
                                    .border(0.5.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)
                                    .clickable { selected = color }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSelectColor(selected); onDismiss() }) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
fun SizePickerDialog(
    currentSize: Float,
    onSizeChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Brush Size", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(32.dp))

                Box(contentAlignment = Alignment.Center, modifier = Modifier.height(100.dp)) {
                    Box(Modifier.size((currentSize).dp).background(Color.White, CircleShape))
                }

                Slider(value = currentSize, onValueChange = onSizeChange, valueRange = 5f..150f)

                Button(onClick = onDismiss, modifier = Modifier.padding(top=16.dp)) { Text("Done") }
            }
        }
    }
}
