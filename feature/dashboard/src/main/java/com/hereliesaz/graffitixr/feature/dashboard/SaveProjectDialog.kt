package com.hereliesaz.graffitixr.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hereliesaz.graffitixr.design.theme.AppStrings

@Composable
fun SaveProjectDialog(
    initialName: String,
    onDismissRequest: () -> Unit,
    onSaveRequest: (String) -> Unit,
    strings: AppStrings,
    isBusy: Boolean = false
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    fun submit() {
        val trimmed = name.trim()
        if (!isBusy && trimmed.isNotEmpty()) onSaveRequest(trimmed)
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isBusy,
            dismissOnClickOutside = !isBusy,
        )
    ) {
        // Keep the entire dialog in Compose's modal window. Do not use AzTextBox here: its
        // rail-oriented submit handling allowed the same pointer gesture to survive the dialog's
        // create/dismiss transition and reach controls in the activity underneath. In practice a
        // tap on SAVE could create the project and then activate Export, producing the misleading
        // "Image saved to gallery" toast and leaving the user looking at Settings.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .background(Color(0xFF101010), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !isBusy,
                    singleLine = true,
                    label = { Text(strings.editor.saveProjectHint) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { submit() },
                        enabled = !isBusy && name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("SAVING")
                        } else {
                            Text(strings.common.save)
                        }
                    }
                }
            }
        }
    }
}
