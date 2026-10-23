package com.hereliesaz.graffitixr.feature.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.hereliesaz.graffitixr.design.R as DesignR
import com.hereliesaz.graffitixr.design.components.GxrDialog
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.HotPink

@Composable
fun SaveProjectDialog(
    initialName: String,
    onDismissRequest: () -> Unit,
    onSaveRequest: (String) -> Unit,
    strings: AppStrings,
    isBusy: Boolean = false
) {
    // Project Save is persistence only. Sharing/exporting is never initiated by this dialog.
    // Force re-initialization if initialName changes, ensuring the field is editable
    // and correctly populated when the dialog appears.
    var name by remember(initialName) { mutableStateOf(initialName) }
    val save = { if (!isBusy && name.isNotBlank()) onSaveRequest(name.trim()) }

    GxrDialog(
        title = stringResource(DesignR.string.save_project_title),
        confirmLabel = strings.common.save,
        onConfirm = save,
        confirmEnabled = !isBusy && name.isNotBlank(),
        dismissLabel = strings.common.cancel,
        onDismiss = { if (!isBusy) onDismissRequest() },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            enabled = !isBusy,
            singleLine = true,
            placeholder = { Text(strings.editor.saveProjectHint) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = HotPink,
                unfocusedBorderColor = HotPink.copy(alpha = 0.6f),
                cursorColor = HotPink,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
