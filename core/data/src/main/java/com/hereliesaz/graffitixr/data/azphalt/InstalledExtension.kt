package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest

/**
 * An azphalt extension that has been verified and unpacked into app storage under
 * `filesDir/extensions/<id>/`. [manifest] is the parsed manifest.json; [dir] is the absolute path to
 * the unpacked tree; [installedAt] is epoch millis.
 */
data class InstalledExtension(
    val manifest: AzphaltManifest,
    val dir: String,
    val installedAt: Long,
) {
    val id: String get() = manifest.id

    /** Absolute path to a payload file within the unpacked extension (e.g. an asset's LUT). */
    fun filePath(relative: String): String = "$dir/$relative"
}
