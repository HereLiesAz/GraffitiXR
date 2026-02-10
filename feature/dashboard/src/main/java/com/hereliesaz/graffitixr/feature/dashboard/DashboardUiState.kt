package com.hereliesaz.graffitixr.feature.dashboard

import com.hereliesaz.graffitixr.common.model.GpsData
import com.hereliesaz.graffitixr.common.model.GraffitiProject

data class DashboardUiState(
    val availableProjects: List<GraffitiProject> = emptyList(),
    val showProjectList: Boolean = true,
    val currentProjectId: String? = null,
    val gpsData: GpsData? = null,
    val isLoading: Boolean = false,
    val updateStatusMessage: String? = null,
    val isCheckingForUpdate: Boolean = false
)
