package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.ProjectData
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class ProjectRepositoryImpl(
    private val context: Context,
    private val projectManager: ProjectManager
) : ProjectRepository {

    private val _currentProject = MutableStateFlow<ProjectData?>(null)
    override val currentProject: StateFlow<ProjectData?> = _currentProject.asStateFlow()

    // We might need to hold the bitmaps separately if they aren't in ProjectData
    private var currentTargetImages: List<Bitmap> = emptyList()

    override suspend fun loadProject(projectId: String): Boolean {
        val loaded = projectManager.loadProject(context, projectId) ?: return false
        currentTargetImages = loaded.targetImages
        _currentProject.value = loaded.projectData
        return true
    }

    override suspend fun saveProject() {
        val project = _currentProject.value ?: return
        projectManager.saveProject(context, project, currentTargetImages)
    }

    override suspend fun createNewProject(): String {
        val newId = UUID.randomUUID().toString()
        // We don't necessarily save it immediately, but we set the state
        // Initial state logic from MainViewModel:
        // UiState(showProjectList=false, currentProjectId=UUID.randomUUID().toString()...)
        // We can't fully create a ProjectData without more info, but we can return the ID
        // Or set a blank project.
        // For now, let's just return the ID and let the ViewModel initialize the data.
        return newId
    }

    override suspend fun updateProject(transform: (ProjectData) -> ProjectData) {
        _currentProject.update { current ->
            if (current != null) transform(current) else null
        }
    }

    override suspend fun getProjectList(): List<String> {
        return projectManager.getProjectList(context)
    }

    override fun getMapPath(projectId: String): String {
        return projectManager.getMapPath(context, projectId)
    }
}
