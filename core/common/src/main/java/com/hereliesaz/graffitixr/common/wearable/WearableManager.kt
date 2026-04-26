package com.hereliesaz.graffitixr.common.wearable

import android.app.Activity
import android.content.Context
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the connection and lifecycle of Meta Wearable devices.
 */
@Singleton
class WearableManager @Inject constructor() {

    /**
     * Observe the registration state with Meta wearables.
     */
    val registrationState: StateFlow<RegistrationState> get() = Wearables.registrationState

    /**
     * Start the registration process with the Meta AI app.
     */
    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    /**
     * Check if a wearable device is currently connected and registered.
     */
    fun isRegistered(): Boolean {
        return Wearables.registrationState.value is RegistrationState.Registered
    }

    // Additional session and camera management can be added here
}
