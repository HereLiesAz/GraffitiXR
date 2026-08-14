package com.hereliesaz.graffitixr.feature.dashboard

import com.hereliesaz.graffitixr.common.model.GpsData
import com.hereliesaz.graffitixr.common.model.GraffitiProject

data class DashboardUiState(
    val availableProjects: List<GraffitiProject> = emptyList(),
    val showProjectList: Boolean = true,
    val currentProjectId: String? = null,
    // The active project's real display name, kept in lockstep with currentProjectId. Distinct from
    // currentProjectId (a UUID unfit for display) so any UI that needs to show or pre-fill the
    // project's name — e.g. the rename dialog — has an actual name to read instead of falling back
    // to the id.
    val currentProjectName: String? = null,
    val gpsData: GpsData? = null,
    val isLoading: Boolean = false,
    val updateStatusMessage: String? = null,
    val isCheckingForUpdate: Boolean = false,
    val updateUrl: String? = null,
    val showNewProjectDialog: Boolean = false,
    // True while onCreateProject's async create+update is in flight. The dialog's confirm guards on
    // this so a second tap before the first create completes can't spawn a duplicate project.
    val isCreatingProject: Boolean = false,
    // Set when importProject fails (bad zip, unsupported format, etc.) so the UI can surface why the
    // spinner stopped instead of failing silently. Cleared by dismissImportError() once shown.
    val importErrorMessage: String? = null
)
