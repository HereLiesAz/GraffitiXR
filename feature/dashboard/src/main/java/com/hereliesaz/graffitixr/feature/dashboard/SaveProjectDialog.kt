package com.hereliesaz.graffitixr.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hereliesaz.aznavrail.AzTextBox
import com.hereliesaz.graffitixr.design.theme.AppStrings

@Composable
fun SaveProjectDialog(
    initialName: String,
    onDismissRequest: () -> Unit,
    onSaveRequest: (String) -> Unit,
    strings: AppStrings
) {
    // Force re-initialization if initialName changes, ensuring the field is editable
    // and correctly populated when the dialog appears.
    var name by remember(initialName) { mutableStateOf(initialName) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onDismissRequest() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(24.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { } // prevent dismissing when clicking content
            ) {
                AzTextBox(
                    value = name,
                    onValueChange = { name = it },
                    hint = strings.editor.saveProjectHint,
                    // Bring back the Hot Pink outline (default)
                    onSubmit = { text ->
                        if (text.isNotBlank()) {
                            onSaveRequest(text)
                        }
                    },
                    submitButtonContent = {
                        Text(strings.common.save, color = Color.White)
                    }
                )
            }
        }
    }
}
