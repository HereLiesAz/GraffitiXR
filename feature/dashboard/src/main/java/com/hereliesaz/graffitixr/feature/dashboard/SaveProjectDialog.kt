package com.hereliesaz.graffitixr.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
    strings: AppStrings,
    isBusy: Boolean = false
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isBusy,
            dismissOnClickOutside = !isBusy,
        )
    ) {
        // A real modal scrim. The old implementation used a full-screen clickable Box as a
        // home-grown outside-click detector. During the create -> navigate transition that left
        // AzNavRail/Settings visually and interactively entangled with the project dialog. Android's
        // Dialog window already owns outside/back dismissal, so there is no reason for a second
        // pointer surface here.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(24.dp)
                    .background(Color(0xFF101010), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                AzTextBox(
                    value = name,
                    enabled = !isBusy,
                    onValueChange = { name = it },
                    hint = strings.editor.saveProjectHint,
                    onSubmit = { text ->
                        if (!isBusy && text.isNotBlank()) {
                            onSaveRequest(text.trim())
                        }
                    },
                    submitButtonContent = {
                        if (isBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                                Text("SAVING", color = Color.White)
                            }
                        } else {
                            Text(strings.common.save, color = Color.White)
                        }
                    }
                )
            }
        }
    }
}
