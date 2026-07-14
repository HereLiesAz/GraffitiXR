package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus

/**
 * An azphalt extension that has been verified and unpacked into app storage under
 * `filesDir/extensions/<id>/`. [manifest] is the parsed manifest.json; [dir] is the absolute path to
 * the unpacked tree; [installedAt] is epoch millis; [signature] is the provenance established at
 * install (and re-derived on rescan) — a valid signature is tamper-evidence, trust is separate.
 */
data class InstalledExtension(
    val manifest: AzphaltManifest,
    val dir: String,
    val installedAt: Long,
    val signature: SignatureStatus = SignatureStatus.UNSIGNED,
) {
    val id: String get() = manifest.id

    /** Absolute path to a payload file within the unpacked extension (e.g. an asset's LUT). */
    fun filePath(relative: String): String = "$dir/$relative"
}
