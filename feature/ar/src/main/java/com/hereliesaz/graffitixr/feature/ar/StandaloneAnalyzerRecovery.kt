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

/**
 * What the top status pill says while there is no usable lock (no registered target yet, or the
 * tracking state is still initializing), as a typed [StandaloneText] resolved at the UI edge. A fatal
 * analyzer never shows the "Preparing…" / "Finding…" progress text — nothing is in progress — but its
 * real failure, or [StandaloneTextKey.TRACKING_STOPPED] when it has none.
 */
internal fun standaloneInitializingStatusText(
    analyzerFatal: Boolean,
    fatalMessage: StandaloneText?,
    currentFailure: StandaloneFailureEvent?,
    referenceReady: Boolean,
    hasReference: Boolean,
): StandaloneText {
    if (analyzerFatal) {
        return fatalMessage ?: currentFailure?.userText ?: StandaloneText(StandaloneTextKey.TRACKING_STOPPED)
    }
    currentFailure
        ?.takeIf { it.severity != StandaloneFailureSeverity.FATAL }
        ?.let { return it.userText }
    return StandaloneText(
        when {
            !referenceReady && !hasReference -> StandaloneTextKey.STATUS_MAPPING
            // SphereSLAM 0.24.3 builds the KPM matcher on its own background thread; until the build
            // lands every frame carries no pose. That is not a failure and the target exists, so
            // don't ask for a new one.
            !referenceReady -> StandaloneTextKey.STATUS_PREPARING
            else -> StandaloneTextKey.STATUS_FINDING
        }
    )
}
