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