package com.hereliesaz.graffitixr.common.wearable

import android.app.Activity
import com.hereliesaz.graffitixr.common.sensor.SensorSource
import kotlinx.coroutines.flow.StateFlow

enum class GlassCapability {
    CAMERA_FEED, SPATIAL_DISPLAY, IMU_TRACKING, HAND_TRACKING
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

interface SmartGlassProvider : SensorSource {
    val name: String
    val capabilities: Set<GlassCapability>
    val connectionState: StateFlow<ConnectionState>

    fun startRegistration(activity: Activity) {}
    fun connect()
    fun disconnect()
}
