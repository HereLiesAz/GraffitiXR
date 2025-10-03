package com.hereliesaz.graffitixr

import android.app.Application
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
class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}