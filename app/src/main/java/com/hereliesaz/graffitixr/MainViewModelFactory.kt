package com.hereliesaz.graffitixr

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Factory for creating a [MainViewModel] with a [SavedStateHandle].
 *
 * This class is responsible for instantiating the [MainViewModel] and passing it the
 * necessary dependencies, including the [Application] context and the [SavedStateHandle]
 * for state persistence. It extends [AbstractSavedStateViewModelFactory] to automatically
 * handle the creation and provision of the [SavedStateHandle].
 *
 * @param application The application instance, used for accessing the application context.
 * @param owner The [SavedStateRegistryOwner] (typically an Activity or Fragment) which provides
 * access to the saved state.
 * @param defaultArgs Optional default arguments to be passed to the ViewModel.
 */
class MainViewModelFactory(
    private val application: Application,
    owner: SavedStateRegistryOwner,
    defaultArgs: Bundle? = null
) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application, handle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}