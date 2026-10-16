package com.hereliesaz.graffitixr

/**
 * An export that is waiting on the API 26-28 storage-permission dialog.
 *
 * A value, not a lambda: the request has to survive the activity being recreated while the system
 * dialog is up (rotation is handled in place, but a locale switch, a display-size change or low-memory
 * recreation are not), and a closure over a composition cannot be saved or re-run by the new one. The
 * kind is saved in the instance state by name and the NEW composition runs the matching action after
 * the grant.
 */
internal enum class GalleryExportRequest {
    /** The Export rail button. */
    RAIL_EXPORT,
    ;

    companion object {
        /** Inverse of [name] for restored instance state; null for anything unrecognised. */
        fun fromSavedName(name: String?): GalleryExportRequest? =
            entries.firstOrNull { it.name == name }
    }
}

/** What the permission answer means for the export that asked. */
internal sealed interface GalleryPermissionOutcome {
    /** Granted, and the request that asked is known: run it. */
    data class Run(val request: GalleryExportRequest) : GalleryPermissionOutcome

    /** Denied, or the request that asked is gone: say so rather than doing nothing. */
    data object Fail : GalleryPermissionOutcome
}

internal object GalleryExportPolicy {
    /**
     * [pending] is null when the answer arrived with no request to attach it to (the saved state that
     * carried it was lost). Both that and a denial end in [GalleryPermissionOutcome.Fail], which the
     * caller surfaces as a toast: a silent no-op leaves the user tapping Export with no feedback.
     */
    fun onPermissionResult(pending: GalleryExportRequest?, granted: Boolean): GalleryPermissionOutcome =
        if (granted && pending != null) GalleryPermissionOutcome.Run(pending) else GalleryPermissionOutcome.Fail
}

/**
 * Whether the editor route that the NavController restored should trigger a reopen of the last open
 * project - decided once per process, on the first route the controller reports.
 *
 * The earlier effect ran on ANY transition to a non-library route with no project loaded, so closing
 * or deleting a project (route still non-library for a moment, the stored "last open" id still being
 * forgotten asynchronously) reopened it. The only case restore exists for is process death: the
 * system hands back an editor route with nothing loaded. That is knowable at the first route the
 * controller reports, and only then.
 */
internal object ProjectRestorePolicy {
    enum class Decision {
        /** The controller has not reported a route yet; ask again on the next one. */
        WAIT,

        /** Nothing to restore; this decision is final for the process. */
        SKIP,

        /** Reopen the last project (falling back to the library if it cannot be loaded). */
        RESTORE,
    }

    fun decide(
        alreadyDecided: Boolean,
        route: String?,
        libraryRoute: String,
        openProjectId: String?,
    ): Decision = when {
        alreadyDecided -> Decision.SKIP
        route == null -> Decision.WAIT
        route == libraryRoute || openProjectId != null -> Decision.SKIP
        else -> Decision.RESTORE
    }
}
