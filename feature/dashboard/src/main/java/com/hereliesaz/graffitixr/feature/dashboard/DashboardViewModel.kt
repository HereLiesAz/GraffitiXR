package com.hereliesaz.graffitixr.feature.dashboard

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.GpsData
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val projects = projectRepository.getProjects()
            _uiState.update {
                it.copy(
                    availableProjects = projects,
                    isLoading = false
                )
            }
        }
    }

    fun onNewProject(isRightHanded: Boolean) {
        viewModelScope.launch {
            // Auto-assigned filename (UUID based or Timestamp)
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val newProject = projectRepository.createProject("Project $timestamp")
            _uiState.update { 
                it.copy(
                    showProjectList = false, 
                    currentProjectId = newProject.id 
                ) 
            }
        }
    }

    fun openProject(project: GraffitiProject) {
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
