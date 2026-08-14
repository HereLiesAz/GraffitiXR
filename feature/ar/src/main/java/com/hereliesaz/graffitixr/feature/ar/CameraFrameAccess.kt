package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

/**
 * Timestamp (ns) of the most recent ARCore frame the renderer has processed, or 0 if none has
 * arrived yet. Lives in :feature:ar so the :app module can read camera-feeding health WITHOUT
 * depending on the ARCore SDK directly: ArRenderer.latestFrame is an AtomicReference of
 * com.google.ar.core.Frame, a type :app cannot access, so callers there only ever see this Long.
 *
 * Reads [ArRenderer.latestFrameTimestampNs] — a plain `@Volatile Long` the GL thread caches
 * alongside [ArRenderer.latestFrame] — rather than `renderer.latestFrame.get()?.timestamp`. This
 * function is polled ~1 Hz from a Compose coroutine (see MainScreen), i.e. off the GL thread, and
 * `Frame.getTimestamp()` is a native ARCore call: [ArRenderer.latestFrame]'s own field doc warns
 * against calling Session/Frame methods on it off the GL thread, since `AtomicReference` only
 * makes the REFERENCE safe to hand across threads, not the native call inside the [Frame] it
 * points to. `latestFrameTimestampNs` is written only by the GL thread and is a value type, so
 * reading it here touches no ARCore native state.
 */
fun lastArFrameTimestampNs(renderer: ArRenderer): Long = renderer.latestFrameTimestampNs
