package com.hereliesaz.graffitixr.common.model

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.Serializable
import com.hereliesaz.graffitixr.common.serialization.UriSerializer
import com.hereliesaz.graffitixr.common.serialization.OffsetSerializer
import com.hereliesaz.graffitixr.common.serialization.BlendModeSerializer
import java.util.UUID

/**
 * Represents a persistent image layer in a [GraffitiProject].
 * Unlike the transient [Layer] used in UI state, this class is serializable
 * and stores the URI reference rather than the loaded Bitmap.
 *
 * @property id Unique identifier for the layer.
 * @property name Display name of the layer.
 * @property uri The content URI of the image file.
 * @property scale The scale factor of the layer.
 * @property offset The 2D translation offset.
 * @property rotationX Rotation around the X-axis (degrees).
 * @property rotationY Rotation around the Y-axis (degrees).
 * @property rotationZ Rotation around the Z-axis (degrees).
 * @property opacity The opacity (alpha) of the layer (0.0 - 1.0).
 * @property blendMode The blending mode used for composition.
 * @property brightness Brightness adjustment (-1.0 to 1.0, 0 is neutral).
 * @property contrast Contrast adjustment (0.0 to 2.0+, 1.0 is neutral).
 * @property saturation Saturation adjustment (0.0 to 2.0+, 1.0 is neutral).
 * @property colorBalanceR Red channel multiplier (1.0 is neutral).
 * @property colorBalanceG Green channel multiplier (1.0 is neutral).
 * @property colorBalanceB Blue channel multiplier (1.0 is neutral).
 * @property isImageLocked If true, the layer cannot be transformed or edited.
 * @property isVisible If false, the layer is not rendered.
 */
@Serializable
data class OverlayLayer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layer",

    @Serializable(with = UriSerializer::class)
    val uri: Uri,

    // Transforms
    val scale: Float = 1.0f,
    @Serializable(with = OffsetSerializer::class)
    val offset: Offset = Offset.Zero,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,

    // Visuals
    val opacity: Float = 1.0f,
    @Serializable(with = BlendModeSerializer::class)
    val blendMode: BlendMode = BlendMode.SrcOver,

    // Color Correction
    val brightness: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorBalanceR: Float = 1.0f, // Changed default to 1.0 based on EditorViewModel usage (multiply)
    val colorBalanceG: Float = 1.0f,
    val colorBalanceB: Float = 1.0f,

    val isImageLocked: Boolean = false, // Renamed from isLocked to match EditorViewModel
    val isVisible: Boolean = true
)
