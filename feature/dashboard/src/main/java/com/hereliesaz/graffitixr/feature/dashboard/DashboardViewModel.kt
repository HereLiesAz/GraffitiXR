package com.hereliesaz.graffitixr.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = repository.getProjects()
            _uiState.update { it.copy(availableProjects = list, isLoading = false) }
        }
    }

    fun openProject(project: GraffitiProject) {
        viewModelScope.launch { repository.loadProject(project.id) }
    }

    fun onNewProject(isRightHanded: Boolean) {
        viewModelScope.launch {
            val p = repository.createProject("New Project")
            repository.updateProject(p.copy(isRightHanded = isRightHanded))
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            repository.deleteProject(projectId)
            loadAvailableProjects()
        }
    }
}

