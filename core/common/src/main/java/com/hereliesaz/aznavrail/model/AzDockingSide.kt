package com.hereliesaz.aznavrail.model

/**
 * Defines the docking side of the AzNavRail.
 *
 * NOTE: This class is manually added to workaround a dependency resolution issue
 * with 'com.github.HereLiesAz:aznavrail-annotation:7.0' (Unauthorized/Timeout on JitPack).
 * The 'AzNavRail' library depends on this class, but we are excluding the annotation module
 * to allow the build to proceed.
 */
enum class AzDockingSide {
    LEFT,
    RIGHT
}
