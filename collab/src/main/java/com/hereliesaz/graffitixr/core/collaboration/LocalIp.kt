package com.hereliesaz.graffitixr.core.collaboration

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

internal object LocalIp {

    /**
     * Returns an IPv4 address the co-op guest can actually reach us on, or null when the device
     * has no non-loopback IPv4.
     *
     * The previous implementation walked `NetworkInterface.getNetworkInterfaces()` and picked the
     * first non-loopback IPv4 the OS happened to enumerate. On a device with cellular + Wi-Fi (or
     * an active VPN), that could return a `rmnet_data0` cellular IP or a `tun0` VPN IP — both
     * unreachable from a guest sharing the LAN — and QR pairing would silently fail because the
     * host advertised an address nobody on Wi-Fi could hit.
     *
     * The standard fix is to ask the OS which source address it would use to reach a routable
     * destination. `DatagramSocket.connect(...)` on UDP sets up socket state without emitting a
     * packet, and `localAddress` then reports the source address matching the default route —
     * which is precisely what the LAN guest will see. Falls back to the old interface scan only
     * if the connect fails (no networking at all).
     */
    fun discover(): String? {
        val fromRoute = try {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName("8.8.8.8"), 53)
                (s.localAddress as? Inet4Address)?.hostAddress
            }
        } catch (_: Exception) {
            null
        }
        if (fromRoute != null && !fromRoute.startsWith("0.") && fromRoute != "127.0.0.1") {
            return fromRoute
        }
        // Fallback: legacy scan, kept so a temporarily offline device (no default route) still
        // reports its LAN address for hosting on an ad-hoc Wi-Fi hotspot.
        return NetworkInterface.getNetworkInterfaces().toList().firstNotNullOfOrNull { iface ->
            if (!iface.isUp || iface.isLoopback) return@firstNotNullOfOrNull null
            iface.inetAddresses.toList().firstOrNull {
                !it.isLoopbackAddress && it.address.size == 4 // IPv4
            }?.hostAddress
        }
    }
}
