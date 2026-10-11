package com.hereliesaz.graffitixr.feature.ar

/**
 * What the standalone overlay does after its analyzer reports a fatal error.
 *
 * A fatal error is terminal for the analyzer AND for SphereSLAM's `PhotosphereTrackingSession`
 * inside it (the session ignores every later frame and never clears its fatal flag), so restoring
 * UI state alone can never resume tracking: any path that wants tracking back must recreate the
 * analyzer (the overlay bumps its analyzer restart generation, a key of the analyzer effect).
 */
internal enum class StandaloneFatalRecovery {
    /**
     * A replacement target was too weak and a previously validated target exists: put the previous
     * target back and recreate the analyzer so it tracks that target again.
     */
    RESTORE_PREVIOUS_AND_RESTART,

    /** Too weak with nothing to fall back to: tell the user to recapture a richer target. */
    REPORT_TOO_WEAK,

    /** Any other terminal failure: show its classified message. */
    REPORT_FAILURE,
}

internal fun standaloneFatalRecovery(
    referenceTooWeak: Boolean,
    hasPreviousValidatedReference: Boolean,
): StandaloneFatalRecovery = when {
    referenceTooWeak && hasPreviousValidatedReference ->
        StandaloneFatalRecovery.RESTORE_PREVIOUS_AND_RESTART
    referenceTooWeak -> StandaloneFatalRecovery.REPORT_TOO_WEAK
    else -> StandaloneFatalRecovery.REPORT_FAILURE
}

/**
 * Whether "Recapture Target" must also recreate the analyzer: only when the current one is fatal
 * (a live analyzer just takes the new target through `updatePrecisionLayer`, keeping its session).
 */
internal fun shouldRestartAnalyzerOnRecapture(analyzerFatal: Boolean): Boolean = analyzerFatal

/** Shown when a fatal analyzer has no more specific message. */
internal const val STANDALONE_TRACKING_STOPPED_MESSAGE =
    "SphereSLAM tracking stopped. Recapture the target to restart it."

/**
 * Text of the top status pill while there is no usable lock (no registered target yet, or the
 * tracking state is still initializing). A fatal analyzer never shows the "Preparing…" /
 * "Finding…" progress text — nothing is in progress — but its real failure.
 */
internal fun standaloneInitializingStatusText(
    analyzerFatal: Boolean,
    fatalMessage: String?,
    currentFailure: StandaloneFailureEvent?,
    referenceReady: Boolean,
    hasReference: Boolean,
): String {
    if (analyzerFatal) {
        return fatalMessage ?: currentFailure?.userMessage ?: STANDALONE_TRACKING_STOPPED_MESSAGE
    }
    currentFailure
        ?.takeIf { it.severity != StandaloneFailureSeverity.FATAL }
        ?.let { return it.userMessage }
    return when {
        !referenceReady && !hasReference -> "SphereSLAM mapping • tap Target to add fingerprint"
        // SphereSLAM 0.24.3 builds the KPM matcher on its own background thread; until the build
        // lands every frame carries no pose. That is not a failure and the target exists, so
        // don't ask for a new one.
        !referenceReady -> "Preparing fingerprint target…"
        else -> "Finding fingerprint target…"
    }
}
