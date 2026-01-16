package com.hereliesaz.graffitixr.data

import android.net.Uri
import android.os.Parcelable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.utils.BlendModeParceler
import com.hereliesaz.graffitixr.utils.OffsetListParceler
import com.hereliesaz.graffitixr.utils.OffsetParceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class OverlayLayer(
    val id: String,
    var name: String,
    @Serializable(with = UriSerializer::class)
    val uri: Uri,
    @Serializable(with = UriSerializer::class)
    val originalUri: Uri? = null,
    @Serializable(with = UriSerializer::class)
    val backgroundRemovedUri: Uri? = null,

    // Transformations
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    @Serializable(with = OffsetSerializer::class)
    val offset: @WriteWith<OffsetParceler> Offset = Offset.Zero,

    // Adjustments
    val opacity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val colorBalanceR: Float = 1f,
    val colorBalanceG: Float = 1f,
    val colorBalanceB: Float = 1f,

    // Note: We are not serializing curvesPoints to JSON for now to simplify,
    // or we assume it's empty during JSON serialization if no serializer is strictly needed.
    // If needed, we'd need a List<Offset> serializer.
    @Serializable(with = OffsetListSerializer::class)
    val curvesPoints: @WriteWith<OffsetListParceler> List<Offset> = emptyList(),

    @Serializable(with = BlendModeSerializer::class)
    val blendMode: @WriteWith<BlendModeParceler> BlendMode = BlendMode.SrcOver,

    // State
    val isLocked: Boolean = false,
    val isVisible: Boolean = true
) : Parcelable

// Helper for the List<Offset> since it's not in Serializers.kt yet
object OffsetListSerializer : kotlinx.serialization.KSerializer<List<Offset>> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(OffsetSerializer)
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: List<Offset>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder) = delegate.deserialize(decoder)
}