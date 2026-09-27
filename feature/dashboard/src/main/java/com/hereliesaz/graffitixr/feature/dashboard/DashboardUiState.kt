package com.hereliesaz.graffitixr.feature.dashboard

import com.hereliesaz.graffitixr.common.model.GraffitiProject

data class DashboardUiState(
    val availableProjects: List<GraffitiProject> = emptyList(),
    val currentProjectId: String? = null,
    // The active project's real display name, kept in lockstep with currentProjectId. Distinct from
    // currentProjectId (a UUID unfit for display) so any UI that needs to show or pre-fill the
    // project's name — e.g. the rename dialog — has an actual name to read instead of falling back
    // to the id.
    val currentProjectName: String? = null,
    val isLoading: Boolean = false,
    val updateStatusMessage: String? = null,
    val isCheckingForUpdate: Boolean = false,
    val updateUrl: String? = null,
    val showNewProjectDialog: Boolean = false,
    // True while onCreateProject's (and createAndOpenProject's) async create+update is in flight.
    // The real duplicate-prevention guard lives here: both functions check this flag at the top and
    // return early on a second call, so a second tap before the first create completes can't spawn a
    // duplicate project. The dialog itself has no guard of its own — onCreateProject dismisses it in
    // the same update() that sets this flag, so the dialog is already gone by the time this is true.
    val isCreatingProject: Boolean = false,
    // Set when a project load, open, create, or delete fails, so the UI can surface a sanitized
    // error message. Cleared by dismissProjectError() once shown.
    val projectErrorMessage: String? = null,
    // Set when importProject fails (bad zip, unsupported format, etc.) so the UI can surface why the
    // spinner stopped instead of failing silently. Cleared by dismissImportError() once shown.
    val importErrorMessage: String? = null
)
