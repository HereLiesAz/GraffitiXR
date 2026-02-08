package com.hereliesaz.graffitixr.feature.ar

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository

class ArViewModelFactory(
    private val application: Application,
    private val projectRepository: ProjectRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ArViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ArViewModel(application, projectRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
