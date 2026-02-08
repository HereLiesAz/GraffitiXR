package com.hereliesaz.graffitixr

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel

class GraffitiViewModelFactory(
    private val application: Application,
    private val projectManager: ProjectManager,
    private val projectRepository: ProjectRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                MainViewModel(application) as T
            }
            modelClass.isAssignableFrom(EditorViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                EditorViewModel() as T
            }
            modelClass.isAssignableFrom(ArViewModel::class.java) -> {
                @Suppress("UNCHECKED_CAST")
                ArViewModel(application, projectRepository) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
