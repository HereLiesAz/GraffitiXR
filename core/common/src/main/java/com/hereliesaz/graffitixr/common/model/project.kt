package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModified: Long = System.currentTimeMillis(),
    val layers: List<OverlayLayer> = emptyList(),

    // Serialized as generic lists of floats because Compose classes can be tricky
    val drawingPaths: List<List<Pair<Float, Float>>> = emptyList(),

    val progressPercentage: Float = 0f,
    val thumbnailUri: String? = null
)