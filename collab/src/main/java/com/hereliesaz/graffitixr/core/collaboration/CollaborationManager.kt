package com.hereliesaz.graffitixr.core.collaboration

import android.content.Context
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
class CollaborationManager(context: Context) {
    private val discovery = DiscoveryManager(context)
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    init {
        System.loadLibrary("graffitixr")
    }

    /**
     * Start accepting peer connections.
     */
    suspend fun startServer(projectFile: File) = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(0)
            val port = serverSocket!!.localPort
            discovery.startBroadcasting(port)
            isRunning = true

            while (isRunning) {
                val client = try {
                    serverSocket?.accept()
                } catch (e: Exception) {
                    null
                } ?: break
                
                // Handle each client in a separate coroutine if we want multi-peer,
                // but for now, the original logic is fine for a simple session.
                handlePeerHandshake(client, projectFile, isHost = true)
            }
        } catch (e: Exception) {
            android.util.Log.e("CollabManager", "Server error", e)
        } finally {
            stopServer()
        }
    }

    fun stopServer() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
        discovery.stopBroadcasting()
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

    fun stopDiscovery() {
        discovery.stopDiscovery()
    }

    /**
     * Connect to a discovered peer.
     */
    suspend fun connectToPeer(host: InetAddress, port: Int, saveProjectTo: File): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 5000) // 5s timeout
            handlePeerHandshake(socket, saveProjectTo, isHost = false)
            true
        } catch (e: Exception) {
            android.util.Log.e("CollabManager", "Connection failed", e)
            false
        }
    }

    private fun handlePeerHandshake(socket: Socket, projectFile: File, isHost: Boolean) {
        socket.use { s ->
            s.soTimeout = 10000 // 10s timeout for data transfer
            val output = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())

            try {
                if (isHost) {
                    // 1. Send Fingerprint
                    val myFingerprint = nativeExportFingerprint() ?: byteArrayOf()
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
                    if (fpSize > 0 && fpSize < 10 * 1024 * 1024) { // Safety check 10MB
                        val peerFingerprint = ByteArray(fpSize)
                        input.readFully(peerFingerprint)
                        nativeAlignToPeer(peerFingerprint)
                    }
                    
                    // 2. Receive Project File
                    val fileSize = input.readInt()
                    if (fileSize > 0 && fileSize < 100 * 1024 * 1024) { // Safety check 100MB
                        val fileBytes = ByteArray(fileSize)
                        input.readFully(fileBytes)
                        projectFile.writeBytes(fileBytes)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CollabManager", "Handshake failed", e)
            }
        }
    }

    // Native Bridge Hooks
    private external fun nativeExportFingerprint(): ByteArray?
    private external fun nativeAlignToPeer(data: ByteArray)
}
