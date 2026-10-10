package com.hereliesaz.graffitixr.common.model

/**
 * What the hybrid (ARCore-primary, SphereSLAM/KPM-sidecar) correction path decided about the most
 * recent KPM observation, and the evidence it decided on.
 *
 * [FusionDiagnostics] says what `PoseFusion` did; this says why a KPM observation did or did not
 * reach it. The artist-visible symptom of every non-[HybridKpmOutcome.ACCEPTED] outcome is the
 * same — the overlay rides the ARCore backbone — so the outcome alone is the diagnosis.
 *
 * The record and its gate outcomes are SphereSLAM's sidecar types (they are produced by
 * `com.hereliesaz.sphereslam.sidecar.HybridKpmCorrection`); the aliases keep the app's model
 * vocabulary for the UI, the eval recorder and the diagnostic overlay. Every numeric field is `-1`
 * when not computed for its outcome.
 */
typealias HybridKpmDiagnostics = com.hereliesaz.sphereslam.sidecar.HybridKpmDiagnostics

/** Outcome of one KPM observation, in gate order. Not persisted by ordinal anywhere. */
typealias HybridKpmOutcome = com.hereliesaz.sphereslam.sidecar.HybridKpmOutcome
