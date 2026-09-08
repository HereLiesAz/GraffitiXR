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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

@Composable
fun SaveProjectDialog(
    initialName: String,
    onDismissRequest: () -> Unit,
    onSaveRequest: (String) -> Unit,
    strings: AppStrings,
    isBusy: Boolean = false
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var pendingSubmit by remember { mutableStateOf<String?>(null) }
    val locallySubmitting = pendingSubmit != null
    val busy = isBusy || locallySubmitting

    fun submit() {
        val trimmed = name.trim()
        if (!busy && trimmed.isNotEmpty()) pendingSubmit = trimmed
    }

    // Do not dismiss the Dialog in the same input dispatch that clicked SAVE. AzNavRail lives in
    // the activity window underneath this Dialog and handles its own pointer stream. If project
    // creation completes quickly enough to remove the Dialog while that SAVE gesture is still being
    // dispatched, the tail of the gesture can reach the rail: on real devices this has fired
    // Project -> Share Wall and Project -> Export, opening the .gxr share sheet and also producing
    // the "Image saved to gallery" toast. Keep the modal window alive beyond the gesture before
    // handing creation/save to the caller. The local busy flag also makes a second tap impossible
    // during that guard interval.
    LaunchedEffect(pendingSubmit) {
        val submittedName = pendingSubmit ?: return@LaunchedEffect
        delay(250)
        onSaveRequest(submittedName)
        pendingSubmit = null
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy,
        )
    ) {
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
                    enabled = !busy,
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
                        enabled = !busy && name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
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
