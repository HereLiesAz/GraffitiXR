package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedTransportTest {

    private fun newHost(
        token: String,
        project: ByteArray = ByteArray(256) { (it * 3).toByte() },
    ) = HostSession(
        token = token,
        protocolVersion = 2,
        localDeviceName = "host",
        fingerprintBytes = ByteArray(64) { it.toByte() },
        projectBytes = project,
        projectId = "p1",
        layerCount = 0,
    )

    private fun newGuest(
        port: Int,
        token: String,
        onOp: suspend (Op) -> Unit = {},
        onBulk: suspend (ByteArray, ByteArray) -> Unit = { _, _ -> },
    ) = GuestSession(
        host = "127.0.0.1",
        port = port,
        token = token,
        protocolVersion = 2,
        localDeviceName = "guest",
        onBulkReceived = onBulk,
        onOp = onOp,
        reconnectWindowMs = 2_000L,
        reconnectIntervalMs = 200L,
    )

    @Test
    fun `wrong token is rejected and the host keeps hosting`() = runBlocking {
        val host = newHost(token = "correct")
        val port = host.startListening()

        val badGuest = newGuest(port, token = "wrong")
        badGuest.connect()
        val ended = withTimeout(10_000) { badGuest.state.first { it is CoopSessionState.Ended } }
        assertTrue((ended as CoopSessionState.Ended).reason == CoopSessionState.EndReason.BadToken)

        // A correct-token guest can still join afterward.
        var bulkOk = false
        val goodGuest = newGuest(port, token = "correct", onBulk = { _, _ -> bulkOk = true })
        goodGuest.connect()
        withTimeout(10_000) { while (!bulkOk) delay(50) }

        host.close(CoopSessionState.EndReason.UserLeft)
        goodGuest.close(CoopSessionState.EndReason.UserLeft)
    }

    @Test
    fun `an on-path eavesdropper sees only ciphertext`() = runBlocking {
        val marker = "TOP-SECRET-MURAL-MARKER"
        val project = ByteArray(4096).also {
            System.arraycopy(marker.toByteArray(), 0, it, 1000, marker.length)
        }
        val host = newHost(token = "tok", project = project)
        val hostPort = host.startListening()

        // A transparent TCP proxy between guest and host that copies every byte in both
        // directions into `wire`. This is exactly what an on-path attacker on shared Wi-Fi sees.
        val wire = ByteArrayOutputStream()
        val proxy = ServerSocket(0)
        val proxyPort = proxy.localPort
        val proxyThread = Thread {
            try {
                proxy.accept().use { downstream ->
                    Socket("127.0.0.1", hostPort).use { upstream ->
                        val g2h = pump(downstream, upstream, wire)
                        val h2g = pump(upstream, downstream, wire)
                        g2h.join(8_000); h2g.join(8_000)
                    }
                }
            } catch (_: Exception) { /* closed at teardown */ }
        }
        proxyThread.isDaemon = true
        proxyThread.start()

        var bulkOk = false
        val received = mutableListOf<Op>()
        val guest = newGuest(
            proxyPort,
            token = "tok",
            onBulk = { _, _ -> bulkOk = true },
            onOp = { op -> synchronized(received) { received.add(op) } },
        )
        guest.connect()
        withTimeout(10_000) { while (!bulkOk) delay(50) }

        host.enqueueOp(Op.LayerAdd(Layer(id = marker, name = "n")))
        withTimeout(10_000) {
            while (synchronized(received) { received.none { (it as? Op.LayerAdd)?.layer?.id == marker } }) delay(50)
        }
        // Let the proxy flush the delta bytes it forwarded.
        delay(300)

        val onWire = synchronized(wire) { String(wire.toByteArray(), Charsets.ISO_8859_1) }
        assertTrue("proxy should have observed traffic", onWire.isNotEmpty())
        assertFalse("marker leaked in cleartext on the wire", onWire.contains(marker))

        host.close(CoopSessionState.EndReason.UserLeft)
        guest.close(CoopSessionState.EndReason.UserLeft)
        proxy.close()
    }

    private fun pump(from: Socket, to: Socket, capture: ByteArrayOutputStream): Thread {
        val t = Thread {
            try {
                val buf = ByteArray(8192)
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(capture) { capture.write(buf, 0, n) }
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) { /* peer closed */ }
        }
        t.isDaemon = true
        t.start()
        return t
    }
}
