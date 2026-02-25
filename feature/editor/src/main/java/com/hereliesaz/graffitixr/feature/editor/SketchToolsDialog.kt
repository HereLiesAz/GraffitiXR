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

@Composable
fun ColorPickerDialog(
    currentColor: Color,
    history: List<Color>,
    onSelectColor: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var r by remember { mutableFloatStateOf(currentColor.red) }
    var g by remember { mutableFloatStateOf(currentColor.green) }
    var b by remember { mutableFloatStateOf(currentColor.blue) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select Color", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))

                Box(Modifier.size(60.dp).background(Color(r, g, b), CircleShape).border(2.dp, Color.White, CircleShape))

                Spacer(Modifier.height(16.dp))
                Slider(value = r, onValueChange = { r = it }, colors = SliderDefaults.colors(activeTrackColor = Color.Red))
                Slider(value = g, onValueChange = { g = it }, colors = SliderDefaults.colors(activeTrackColor = Color.Green))
                Slider(value = b, onValueChange = { b = it }, colors = SliderDefaults.colors(activeTrackColor = Color.Blue))

                if (history.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.fillMaxWidth().height(80.dp)) {
                        items(history) { color ->
                            Box(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(30.dp)
                                    .background(color, CircleShape)
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable {
                                        r = color.red; g = color.green; b = color.blue
                                    }
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSelectColor(Color(r, g, b)); onDismiss() }) { Text("Apply") }
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