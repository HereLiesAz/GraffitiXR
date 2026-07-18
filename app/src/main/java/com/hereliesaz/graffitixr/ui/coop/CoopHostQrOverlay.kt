package com.hereliesaz.graffitixr.ui.coop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Color as AColor

@Composable
fun CoopHostQrOverlay(
    qrPayload: String,
    onStopSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Generate the QR off the main thread — a 512x512 per-pixel encode + fill janks composition.
    val bitmap by produceState<Bitmap?>(initialValue = null, qrPayload) {
        value = withContext(Dispatchers.Default) { qrPayload.toQrBitmap(size = 512) }
    }
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
            bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "co-op QR") }
            Button(onClick = onStopSharing) { Text("Stop sharing") }
        }
    }
}

private fun String.toQrBitmap(size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(this, BarcodeFormat.QR_CODE, size, size)
    // One bulk setPixels beats size*size individual setPixel calls (262k for 512) by a wide margin.
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix[x, y]) AColor.BLACK else AColor.WHITE
        }
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}
