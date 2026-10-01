package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend

internal fun testSpatialFrame(
    backend: CoopTrackingBackend = CoopTrackingBackend.ARCORE,
    scale: CoopSpatialScale = CoopSpatialScale.METRIC,
    fingerprintAvailable: Boolean = true,
    revision: Long = 1L,
): CoopSpatialFrame = CoopSpatialFrame(
    hostBackend = backend,
    fingerprintFrameVersion = 1,
    scale = scale,
    fingerprintAvailable = fingerprintAvailable,
    anchorRevision = revision,
    referenceWidthUnits = 1f,
    fingerprintFromWall = listOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    ),
)
