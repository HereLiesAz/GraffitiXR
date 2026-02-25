package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

@Composable
fun CurvesDialog(
    onDismissRequest: () -> Unit,
    onCurvesApplied: (List<Offset>) -> Unit
) {
    var points by remember {
        mutableStateOf(listOf(Offset(0f, 0f), Offset(1f, 1f)))
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Curves") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                CurvesAdjustment(
                    points = points,
                    onPointsChanged = { newPoints -> points = newPoints },
                    onDragEnd = { }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCurvesApplied(points)
                onDismissRequest()
            }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}