package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.graffitixr.common.model.Layer

@Composable
fun PosterOptionsDialog(
    sourceLayerId: String,
    layers: List<Layer>,
    onDismiss: () -> Unit,
    onGenerate: (Float, List<String>) -> Unit
) {
    val stencilLayers = layers.filter { it.stencilSourceId == sourceLayerId || it.id == sourceLayerId }
        .filter { it.stencilType != null }
    
    var selectedLayerIds by remember { mutableStateOf(stencilLayers.map { it.id }.toSet()) }
    var outputSizeMm by remember { mutableFloatStateOf(300f) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GENERATE POSTER",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(Modifier.height(16.dp))
                
                Text("Select layers to include:", color = Color.Gray, fontSize = 14.sp)
                
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .padding(vertical = 8.dp)
                ) {
                    items(stencilLayers) { layer ->
                        val isSelected = selectedLayerIds.contains(layer.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedLayerIds = if (isSelected) selectedLayerIds - layer.id
                                    else selectedLayerIds + layer.id
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Color.Cyan)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(layer.name, color = Color.White)
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text("Physical Size (Width in mm):", color = Color.Gray, fontSize = 14.sp)
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "${outputSizeMm.toInt()} mm",
                        color = Color.Cyan,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(16.dp))
                    Slider(
                        value = outputSizeMm,
                        onValueChange = { outputSizeMm = it },
                        valueRange = 100f..2000f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                    )
                }
                
                Spacer(Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", color = Color.Gray)
                    }
                    Button(
                        onClick = { onGenerate(outputSizeMm, selectedLayerIds.toList()) },
                        enabled = selectedLayerIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan, contentColor = Color.Black)
                    ) {
                        Text("GENERATE PDF")
                    }
                }
            }
        }
    }
}
