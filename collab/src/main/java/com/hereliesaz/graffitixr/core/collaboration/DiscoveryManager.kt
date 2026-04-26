package com.hereliesaz.graffitixr.core.collaboration

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

/**
 * Handles multi-mode peer discovery for AR collaboration.
 * Supports both Network Service Discovery (NSD) and Wi-Fi Direct (P2P).
 */
class DiscoveryManager(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val p2pChannel = p2pManager.initialize(context, context.mainLooper, null)
    
    private val SERVICE_TYPE = "_graffitixr._tcp"
    private val TAG = "GXR_Discovery"

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Start broadcasting presence on the local network (Wi-Fi).
     */
    fun startBroadcasting(port: Int) {
        stopBroadcasting()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "GXR_${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(p0: NsdServiceInfo?) {}
            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
            override fun onServiceUnregistered(p0: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun stopBroadcasting() {
        registrationListener?.let {
            nsdManager.unregisterService(it)
            registrationListener = null
        }
    }

    /**
     * Start looking for peers via Wi-Fi Direct (P2P).
     * Ideal for outdoor sessions without a router.
     */
    fun discoverP2P() {
        p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P Discovery Started")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "P2P Discovery Failed: $reason")
            }
        })
    }

    /**
     * Start looking for peers on the local Wi-Fi network (NSD).
     */
    fun discoverNSD(onPeerFound: (NsdServiceInfo) -> Unit) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE || service.serviceType.contains("graffitixr")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            onPeerFound(resolved)
                        }
                        override fun onResolveFailed(p0: NsdServiceInfo?, p1: Int) {}
                    })
                }
            }
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            nsdManager.stopServiceDiscovery(it)
            discoveryListener = null
        }
    }
}
