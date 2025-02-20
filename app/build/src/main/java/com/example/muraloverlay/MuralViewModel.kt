package com.example.muraloverlay

import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.IgnoredOnParcel

@Parcelize
data class OverlayState(
    var imageUri: Uri? = null,
    var opacity: Float = 0.5f,
    var contrast: Float = 1.0f,
    var saturation: Float = 1.0f,
    var scale: Float = 1.0f,
    var translationX: Float = 0f,
    var translationY: Float = 0f,
    var showGrid: Boolean = true
) : Parcelize

class MuralViewModel : ViewModel() {
    val overlayState = OverlayState()

    @IgnoredOnParcel
    val imageMatrix = Matrix()
}