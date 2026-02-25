package com.hereliesaz.graffitixr.common.security

import android.content.Context
import android.content.Intent
import com.google.android.gms.security.ProviderInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class SecurityProviderState {
    object Loading : SecurityProviderState()
    object Installed : SecurityProviderState()
    data class RecoverableError(val errorCode: Int, val recoveryIntent: Intent?) : SecurityProviderState()
    data class CriticalError(val errorCode: Int) : SecurityProviderState()
}

@Singleton
class SecurityProviderManager @Inject constructor() {

    private val _securityProviderState = MutableStateFlow<SecurityProviderState>(SecurityProviderState.Loading)
    val securityProviderState: StateFlow<SecurityProviderState> = _securityProviderState

    fun installAsync(context: Context) {
        Timber.d("GraffitiXR: Checking Security Provider...")
        ProviderInstaller.installIfNeededAsync(context, object : ProviderInstaller.ProviderInstallListener {
            override fun onProviderInstalled() {
                Timber.d("GraffitiXR: Security Provider installed successfully.")
                _securityProviderState.value = SecurityProviderState.Installed
            }

            override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: Intent?) {
                Timber.e("GraffitiXR: Failed to install Security Provider! Error code: $errorCode")
                if (recoveryIntent != null) {
                     _securityProviderState.value = SecurityProviderState.RecoverableError(errorCode, recoveryIntent)
                } else {
                    _securityProviderState.value = SecurityProviderState.CriticalError(errorCode)
                }
            }
        })
    }
}
