package com.hereliesaz.graffitixr.common.wearable

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Hardware capabilities supported by different smart glasses.
 */
enum class GlassCapability {
    CAMERA_FEED,       // Can provide a perspective-aligned camera feed
    SPATIAL_DISPLAY,   // Has a transparent AR display for projection
    IMU_TRACKING,      // Provides head-tracking (3DoF/6DoF)
    HAND_TRACKING      // Provides hand/gesture input
}

/**
 * Current connection status of the hardware.
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Unified interface for different AI/AR glasses hardware.
 */
interface SmartGlassProvider {
    val name: String
    val capabilities: Set<GlassCapability>
    val connectionState: StateFlow<ConnectionState>

    /**
     * Optional initialization/registration flow (e.g., for Meta's companion app).
     */
    fun startRegistration(activity: Activity) {}

    /**
     * Connect to the hardware.
     */
    fun connect()

    /**
     * Disconnect from the hardware.
     */
    fun disconnect()
}
