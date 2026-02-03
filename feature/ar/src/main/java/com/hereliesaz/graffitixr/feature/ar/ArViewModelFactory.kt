package com.hereliesaz.graffitixr.feature.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hereliesaz.graffitixr.data.RepositoryProvider

class ArViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ArViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ArViewModel(RepositoryProvider.projectRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
