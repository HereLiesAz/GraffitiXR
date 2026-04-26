package com.hereliesaz.graffitixr.common.wearable

import android.app.Activity
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaGlassProvider @Inject constructor() : SmartGlassProvider {
    override val name: String = "Meta Ray-Ban"
    
    override val capabilities: Set<GlassCapability> = setOf(
        GlassCapability.CAMERA_FEED,
        GlassCapability.IMU_TRACKING
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        scope.launch {
            Wearables.registrationState.collect { state ->
                _connectionState.value = when (state) {
                    is RegistrationState.Registered -> ConnectionState.Connected
                    is RegistrationState.Available -> ConnectionState.Disconnected
                    is RegistrationState.Unavailable -> {
                        if (state.error != null) ConnectionState.Error(state.error.toString())
                        else ConnectionState.Disconnected
                    }
                    else -> ConnectionState.Connecting
                }
            }
        }
    }

    override fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    override fun connect() {
        // Registration state is handled by the Meta AI app callback
    }

    override fun disconnect() {
        // Handled globally or by app backgrounding
    }
}
