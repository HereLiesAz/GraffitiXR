package com.hereliesaz.graffitixr.common.wearable

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates connection and lifecycle management for all supported AI/AR glasses.
 */
@Singleton
class WearableManager @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards SmartGlassProvider>
) {
    private val _activeProvider = MutableStateFlow<SmartGlassProvider?>(null)
    val activeProvider: StateFlow<SmartGlassProvider?> = _activeProvider.asStateFlow()

    /**
     * Get the list of all supported hardware providers.
     */
    fun getAvailableProviders(): List<SmartGlassProvider> = providers.toList()

    /**
     * Select a specific hardware provider to use.
     */
    fun selectProvider(provider: SmartGlassProvider) {
        _activeProvider.value?.disconnect()
        _activeProvider.value = provider
        provider.connect()
    }

    /**
     * Start the registration/setup flow for the currently active provider.
     */
    fun startRegistration(activity: Activity) {
        _activeProvider.value?.startRegistration(activity)
    }

    /**
     * Shortcut to check if the Meta provider is registered (backward compatibility).
     */
    fun isRegistered(): Boolean {
        return _activeProvider.value?.let { 
            it.name.contains("Meta") && it.connectionState.value == ConnectionState.Connected
        } ?: false
    }
}
