package com.hereliesaz.graffitixr

import com.hereliesaz.graffitixr.common.model.Layer

private val SAFE_ID_REGEX = Regex("[A-Za-z0-9_-]+")

internal fun sanitize(s: String): String =
    if (s.matches(SAFE_ID_REGEX)) s
    else "h${s.hashCode().toUInt().toString(16)}"

internal fun layerId(layer: Layer, tool: String? = null): String {
    val base = "layer.${sanitize(layer.id)}"
    return if (tool == null) base else "$base.tool.$tool"
}
