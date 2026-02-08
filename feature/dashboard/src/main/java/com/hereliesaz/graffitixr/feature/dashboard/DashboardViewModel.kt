package com.hereliesaz.graffitixr.feature.dashboard

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.GpsData
import com.hereliesaz.graffitixr.common.model.ProjectData
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class DashboardViewModel(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // Note: Repository currently uses StateFlow for currentProject.
            // Loading the project list would typically involve a separate flow or method.
            // For now, keeping the loading state management.
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onNewProject(isRightHanded: Boolean) {
        viewModelScope.launch {
            val newProject = projectRepository.createProject("New Project")
            _uiState.update { 
                it.copy(
                    showProjectList = false, 
                    currentProjectId = newProject.id 
                ) 
            }
        }
    }

    fun openProject(project: ProjectData) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = projectRepository.loadProject(project.id)
            if (result.isSuccess) {
                 _uiState.update { 
                     it.copy(
                         showProjectList = false, 
                         currentProjectId = project.id 
                     ) 
                 }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun updateCurrentLocation(location: Location) {
        val gpsData = GpsData(location.latitude, location.longitude, location.altitude, location.accuracy, location.time)
        _uiState.update { it.copy(gpsData = gpsData) }
        // sortProjects(location) // TODO
    }
}
