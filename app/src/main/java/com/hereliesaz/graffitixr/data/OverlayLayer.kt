package com.hereliesaz.graffitixr.data

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer",
    val uri: Uri,
    val opacity: Float = 1.0f,
    val brightness: Float = 0.0f,
    val contrast: Float = 0.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 0.0f,
    val colorBalanceG: Float = 0.0f,
    val colorBalanceB: Float = 0.0f,
    val scale: Float = 1.0f,
    val rotationX: Float = 0.0f,
    val rotationY: Float = 0.0f,
    val rotationZ: Float = 0.0f,
    val offset: Offset = Offset.Zero,
    val blendMode: Int = 0,
    val isVisible: Boolean = true,
    // NEW: Persist aspect ratio
    val aspectRatio: Float = 1.0f 
) : Parcelable
