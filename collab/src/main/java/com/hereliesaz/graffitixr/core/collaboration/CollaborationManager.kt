package com.hereliesaz.graffitixr.core.collaboration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Orchestrates AR synchronization between local peers.
 */
class CollaborationManager(context: Context) {
    private val discovery = DiscoveryManager(context)
    private var serverSocket: ServerSocket? = null

    init {
        System.loadLibrary("collaboration_bridge")
    }

    /**
     * Start accepting peer connections.
     */
    suspend fun startServer() = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(0) // Assign random available port
        val port = serverSocket!!.localPort
        discovery.startBroadcasting(port)

        while (true) {
            val client = serverSocket?.accept() ?: break
            handlePeerHandshake(client)
        }
    }

    /**
     * Start looking for peers.
     */
    fun startDiscovery(onPeerFound: (java.net.InetAddress, Int) -> Unit) {
        discovery.discoverNSD { info ->
            onPeerFound(info.host, info.port)
        }
        discovery.discoverP2P()
    }

    /**
     * Connect to a discovered peer.
     */
    suspend fun connectToPeer(host: InetAddress, port: Int) = withContext(Dispatchers.IO) {
        val socket = Socket(host, port)
        handlePeerHandshake(socket)
    }

    private fun handlePeerHandshake(socket: Socket) {
        socket.use { s ->
            // 1. Get our visual fingerprint from MobileGS engine
            val myData = nativeExportFingerprint()
            
            // 2. Exchange binary data
            s.getOutputStream().write(myData)
            val peerData = s.getInputStream().readBytes()
            
            // 3. Align local AR session to peer's coordinate system
            nativeAlignToPeer(peerData)
        }
    }

    // Native Bridge Hooks
    private external fun nativeExportFingerprint(): ByteArray
    private external fun nativeAlignToPeer(data: ByteArray)
}
