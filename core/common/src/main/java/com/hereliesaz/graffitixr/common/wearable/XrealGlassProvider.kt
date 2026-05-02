package com.hereliesaz.graffitixr.common.wearable

import android.content.Context
import android.hardware.usb.UsbManager
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XrealGlassProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : SmartGlassProvider {
    override val cameraIntrinsics: CameraIntrinsics = CameraIntrinsics.UNKNOWN

    override val name: String = "Xreal Air"

    override val capabilities: Set<GlassCapability> = setOf(
        GlassCapability.SPATIAL_DISPLAY,
        GlassCapability.IMU_TRACKING
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun connect() {
        checkUsbConnection()
    }

    override fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun checkUsbConnection() {
        val deviceList = usbManager.deviceList
        // Xreal Vendor IDs: 0x3318, 0x0483 (Nreal/Xreal)
        val isXrealFound = deviceList.values.any { 
            it.vendorId == 0x3318 || it.vendorId == 0x0483 
        }

        _connectionState.value = if (isXrealFound) {
            ConnectionState.Connected
        } else {
            ConnectionState.Disconnected
        }
    }
}
