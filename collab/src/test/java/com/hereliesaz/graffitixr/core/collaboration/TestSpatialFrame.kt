package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend

internal fun testSpatialFrame(
    backend: CoopTrackingBackend = CoopTrackingBackend.ARCORE,
    scale: CoopSpatialScale = CoopSpatialScale.METRIC,
    doodleAvailable: Boolean = true,
    revision: Long = 1L,
): CoopSpatialFrame = CoopSpatialFrame(
    hostBackend = backend,
    doodleFrameVersion = 1,
    scale = scale,
    doodleAvailable = doodleAvailable,
    anchorRevision = revision,
    referenceWidthUnits = 1f,
    doodleFromWall = listOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    ),
)
