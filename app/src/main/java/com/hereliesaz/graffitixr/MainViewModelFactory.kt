package com.hereliesaz.graffitixr

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hereliesaz.graffitixr.feature.ar.*

class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val projectManager = ProjectManager() // Initialize dependency
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application, projectManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}