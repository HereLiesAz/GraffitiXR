package com.hereliesaz.graffitixr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Factory for creating a [MainViewModel].
 *
 * This class is responsible for instantiating the [MainViewModel]. While the current
 * ViewModel doesn't have complex dependencies, using a factory is a best practice
 * for ViewModel creation in Android, as it allows for dependencies to be passed
 * in a structured way.
 */
class MainViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}