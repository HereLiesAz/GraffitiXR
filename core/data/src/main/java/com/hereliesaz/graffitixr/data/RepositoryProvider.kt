package com.hereliesaz.graffitixr.data

import android.content.Context
import com.hereliesaz.graffitixr.data.repository.ProjectRepositoryImpl
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository

object RepositoryProvider {
    
    private var _projectRepository: ProjectRepository? = null
    
    val projectRepository: ProjectRepository
        get() = _projectRepository ?: throw IllegalStateException("RepositoryProvider not initialized")

    fun initialize(context: Context) {
        if (_projectRepository == null) {
            val projectManager = ProjectManager()
            _projectRepository = ProjectRepositoryImpl(context.applicationContext, projectManager)
        }
    }
}
