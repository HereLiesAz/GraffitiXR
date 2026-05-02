package com.hereliesaz.graffitixr.core.collaboration

import java.net.NetworkInterface

internal object LocalIp {
    fun discover(): String? {
        return NetworkInterface.getNetworkInterfaces().toList().firstNotNullOfOrNull { iface ->
            if (!iface.isUp || iface.isLoopback) return@firstNotNullOfOrNull null
            iface.inetAddresses.toList().firstOrNull {
                !it.isLoopbackAddress && it.address.size == 4 // IPv4
            }?.hostAddress
        }
    }
}
