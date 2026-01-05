package com.hereliesaz.graffitixr.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hereliesaz.aznavrail.AzTextBox
import com.hereliesaz.aznavrail.AzTextBoxDefaults

@Composable
fun SaveProjectDialog(
    onDismissRequest: () -> Unit,
    onSaveRequest: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            // Configure AzTextBox defaults if needed, though usually done globally.
            // Here we just use it directly.

            AzTextBox(
                hint = "Project Name",
                onSubmit = { text ->
                    if (text.isNotBlank()) {
                        onSaveRequest(text)
                    }
                },
                submitButtonContent = {
                    Text("Save")
                }
            )
        }
    }
}
