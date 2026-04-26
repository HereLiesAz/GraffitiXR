package com.hereliesaz.graffitixr.core.collaboration

import android.content.Context
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Orchestrates AR synchronization between local peers.
 */
class CollaborationManager(context: Context, private val slamManager: SlamManager) {
    private val discovery = DiscoveryManager(context)
    private var serverSocket: ServerSocket? = null

    /**
     * Start accepting peer connections.
     */
    suspend fun startServer(projectFile: File) = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        discovery.startBroadcasting(port)

        while (true) {
            val client = serverSocket?.accept() ?: break
            handlePeerHandshake(client, projectFile, isHost = true)
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
    suspend fun connectToPeer(host: InetAddress, port: Int, saveProjectTo: File) = withContext(Dispatchers.IO) {
        val socket = Socket(host, port)
        handlePeerHandshake(socket, saveProjectTo, isHost = false)
    }

    private fun handlePeerHandshake(socket: Socket, projectFile: File, isHost: Boolean) {
        socket.use { s ->
            val output = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())

            if (isHost) {
                // 1. Send Fingerprint
                val myFingerprint = slamManager.exportFingerprint() ?: byteArrayOf()
                output.writeInt(myFingerprint.size)
                output.write(myFingerprint)

                // 2. Send Project File
                val fileBytes = projectFile.readBytes()
                output.writeInt(fileBytes.size)
                output.write(fileBytes)
                output.flush()
            } else {
                // 1. Receive Fingerprint
                val fpSize = input.readInt()
                val peerFingerprint = ByteArray(fpSize)
                input.readFully(peerFingerprint)
                
                // 2. Receive Project File
                val fileSize = input.readInt()
                val fileBytes = ByteArray(fileSize)
                input.readFully(fileBytes)
                projectFile.writeBytes(fileBytes)

                // 3. Align session
                slamManager.alignToFingerprint(peerFingerprint)
            }
        }
    }
}
