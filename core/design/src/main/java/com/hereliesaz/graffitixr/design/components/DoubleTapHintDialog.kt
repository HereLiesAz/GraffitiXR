package com.hereliesaz.graffitixr.design.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.R

@Composable
fun DoubleTapHintDialog(
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.pro_tip)) },
        text = {
            Text(
                text = stringResource(R.string.double_tap_hint),
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            AzButton(text = stringResource(R.string.ok_button), onClick = onDismissRequest, shape = AzButtonShape.RECTANGLE)
        }
    )
}
