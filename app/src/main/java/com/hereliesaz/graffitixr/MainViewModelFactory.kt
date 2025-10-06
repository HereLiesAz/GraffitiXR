package com.hereliesaz.graffitixr

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Factory for creating a [MainViewModel] with a [SavedStateHandle].
 *
 * This class is responsible for instantiating the [MainViewModel] and passing it the
 * necessary dependencies, including the [Application] context and the [SavedStateHandle]
 * for state persistence.
 */
class MainViewModelFactory : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        val savedStateHandle = extras.createSavedStateHandle()

        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application, savedStateHandle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
