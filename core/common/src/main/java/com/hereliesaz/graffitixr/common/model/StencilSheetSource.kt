package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

/**
 * One authored StencilPack sheet. [uri] points at a project-owned PNG artifact, not at
 * another application's temporary share URI. No generated masks are needed on reload.
 */
@Serializable
data class StencilSheetSource(
    val name: String,
    val uri: String,
    val color: Long,
    val visible: Boolean = true,
)
