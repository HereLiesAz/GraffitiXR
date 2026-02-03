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
            // Repository currently only returns IDs. We need a way to get ProjectData.
            // ProjectRepository should probably have getAvailableProjects() that returns data.
            // For now assuming we can iterate IDs and load metadata
            val ids = projectRepository.getProjectList()
            // Metadata loading logic is currently in ProjectManager, but Repository should expose it.
            // Since I can't change Repository interface easily without breaking impl, I'll assume usage of ProjectManager via Repository or added method.
            // Wait, I added getProjectList() to Repository. I need getProjectMetadata(id).
            // I will add it to Repository interface later.
            
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onNewProject(isRightHanded: Boolean) {
        viewModelScope.launch {
            val newId = projectRepository.createNewProject()
            _uiState.update { 
                it.copy(
                    showProjectList = false, 
                    currentProjectId = newId 
                ) 
            }
        }
    }

    fun openProject(project: ProjectData) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val success = projectRepository.loadProject(project.id)
            if (success) {
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
