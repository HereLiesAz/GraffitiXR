package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

/**
 * Timestamp (ns) of the most recent ARCore frame the renderer has processed, or 0 if none has
 * arrived yet. Lives in :feature:ar so the :app module can read camera-feeding health WITHOUT
 * depending on the ARCore SDK directly: ArRenderer.latestFrame is an AtomicReference of
 * com.google.ar.core.Frame, a type :app cannot access, so callers there only ever see this Long.
 */
fun lastArFrameTimestampNs(renderer: ArRenderer): Long =
    renderer.latestFrame.get()?.timestamp ?: 0L
