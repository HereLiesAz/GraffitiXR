package com.hereliesaz.graffitixr.feature.ar

/**
 * What a caller may do after asking the renderer to hand off its ARCore [Session].
 *
 * A timeout is not partial ownership. The GL thread may still be inside a native ARCore call with a
 * local reference to that same session, so lifecycle code must either defer final cleanup until the
 * renderer lock is actually released or abandon a live reconfiguration attempt entirely.
 */
internal enum class SessionOwnershipAction {
    CLEANUP,
    RECONFIGURE,
}

internal enum class SessionOwnershipDecision {
    PROCEED,
    DEFER_CLEANUP,
    ABORT_RECONFIGURE,
}

internal fun decideSessionOwnership(
    handoffSucceeded: Boolean,
    action: SessionOwnershipAction,
): SessionOwnershipDecision = when {
    handoffSucceeded -> SessionOwnershipDecision.PROCEED
    action == SessionOwnershipAction.CLEANUP -> SessionOwnershipDecision.DEFER_CLEANUP
    else -> SessionOwnershipDecision.ABORT_RECONFIGURE
}
