package com.hereliesaz.graffitixr.ui.coop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun CoopJoinQrScannerOverlay(
    onScanned: (String) -> Unit,
    onCancelled: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents == null) onCancelled()
        else onScanned(result.contents)
    }
    LaunchedEffect(Unit) {
        val opts = ScanOptions().apply {
            setPrompt("Scan host QR")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        launcher.launch(opts)
    }
}
