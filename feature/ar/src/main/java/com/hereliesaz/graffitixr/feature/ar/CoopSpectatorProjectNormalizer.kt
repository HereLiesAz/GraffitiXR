package com.hereliesaz.graffitixr.feature.ar

import android.net.Uri
import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.ModeAdjustment

/**
 * Re-key the host's active AR placement into the guest backend's persistence slot.
 *
 * The co-op wall pose is already calibrated separately. This handles the other half of "same mural
 * on the same wall": the host's pan/scale/rotation/tone must not be read from a stale adjustment
 * belonging to the backend the host is NOT currently using.
 *
 * The returned project is the isolated spectator copy only.
 */
internal fun normalizeCoopSpectatorProject(
    project: GraffitiProject,
    spatialFrame: CoopSpatialFrame,
    guestBackend: CoopTrackingBackend,
    imageDimensions: (Uri) -> Pair<Int, Int>?,
): GraffitiProject {
    val arKey = EditorMode.AR.name
    val hostAdjustment: ModeAdjustment =
        when (spatialFrame.hostBackend) {
            CoopTrackingBackend.ARCORE ->
                project.modeAdjustments[arKey] ?: project.sphereSlamModeAdjustment ?: ModeAdjustment()
            CoopTrackingBackend.SPHERESLAM ->
                project.sphereSlamModeAdjustment ?: project.modeAdjustments[arKey] ?: ModeAdjustment()
        }

    if (guestBackend == spatialFrame.hostBackend) return project

    return when (guestBackend) {
        CoopTrackingBackend.ARCORE -> {
            // supportsGuest() already rejects normalized standalone pages for ARCore. Repeat the
            // condition here so this pure normalizer fails closed if it is ever called directly.
            require(spatialFrame.scale == CoopSpatialScale.METRIC) {
                "ARCore guest cannot consume normalized standalone page units"
            }

            val halfWidth = standaloneBaseHalfWidthMeters(project, imageDimensions)
                ?: project.arDesignHalfWidthM.takeIf { it.isFinite() && it > 0f }
            if (project.design != null) {
                require(halfWidth != null) {
                    "metric standalone host design size could not be reconstructed"
                }
            }

            project.copy(
                modeAdjustments = project.modeAdjustments + (arKey to hostAdjustment),
                arDesignHalfWidthM = halfWidth ?: project.arDesignHalfWidthM,
            )
        }

        CoopTrackingBackend.SPHERESLAM -> {
            // An ARCore-hosted archive can legitimately contain an old standalone target from some
            // earlier visit. The live peer fingerprint is the coordinate object for this session;
            // keeping stale page metadata lets EditorViewModel treat its generation as authoritative
            // placement state. Drop only spectator metadata (files may remain in the isolated dir).
            project.copy(
                sphereSlamReferenceUri = null,
                sphereSlamReferenceWidthMeters = 1f,
                sphereSlamReferencePhysicallyMetric = false,
                sphereSlamAnchorGeneration = 0L,
                sphereSlamPlacementAnchorGeneration = 0L,
                sphereSlamModeAdjustment = hostAdjustment,
                sphereSlamFingerprint = null,
                sphereSlamWallFeatureMap = null,
                sphereSlamAtlasPages = emptyList(),
            )
        }
    }
}

/**
 * Reconstruct the standalone renderer's BASE half-width in metres from the same two aspect ratios
 * it uses at runtime. The user scale remains in ModeAdjustment and is therefore NOT folded here.
 */
private fun standaloneBaseHalfWidthMeters(
    project: GraffitiProject,
    imageDimensions: (Uri) -> Pair<Int, Int>?,
): Float? {
    if (!project.sphereSlamReferencePhysicallyMetric) return null
    val pageUri = project.sphereSlamReferenceUri ?: return null
    val designUri = project.design?.uri ?: return null
    val page = imageDimensions(pageUri) ?: return null
    val design = imageDimensions(designUri) ?: return null
    if (page.first <= 0 || page.second <= 0 || design.first <= 0 || design.second <= 0) return null

    val pageWidth = project.sphereSlamReferenceWidthMeters
    if (!pageWidth.isFinite() || pageWidth <= 0f) return null
    val pageHeight = pageWidth * page.second.toFloat() / page.first.toFloat()
    val fit = fitStandaloneDesignHalfExtents(
        pageWidthUnits = pageWidth,
        pageHeightUnits = pageHeight,
        designWidthPx = design.first,
        designHeightPx = design.second,
    ) ?: return null
    return fit.halfWidth
}
