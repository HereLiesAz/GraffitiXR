package com.hereliesaz.graffitixr.ui.coop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import android.graphics.Color as AColor

@Composable
fun CoopHostQrOverlay(
    qrPayload: String,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(qrPayload) { qrPayload.toQrBitmap(size = 512) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scan to join", color = Color.White)
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "co-op QR")
            Button(onClick = onStopSharing) { Text("Stop sharing") }
        }
    }
}

private fun String.toQrBitmap(size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(this, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) AColor.BLACK else AColor.WHITE)
        }
    }
    return bmp
}
