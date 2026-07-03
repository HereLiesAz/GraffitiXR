package com.hereliesaz.graffitixr.core.collaboration

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression guard for [LocalIp.discover]. The previous implementation returned whatever
 * non-loopback IPv4 the OS enumerated first (any interface: VPN, cellular, Wi-Fi), which could
 * silently break QR pairing when the interface it happened to pick was unreachable from the LAN.
 * The current implementation prefers the source address of the default route, which is what the
 * LAN guest will actually see.
 *
 * These are behavioural sanity checks — the JVM test can't simulate multi-interface routing, but
 * it can prove the function returns a well-formed non-loopback IPv4 whenever this machine has
 * networking configured, and that the result is a plausible source address.
 */
class LocalIpTest {

    @Test
    fun `discover returns a non-loopback IPv4 or null when offline`() {
        val ip = LocalIp.discover()
        if (ip == null) {
            // JVM test host may genuinely have no non-loopback interface (CI sandbox).
            return
        }
        assertTrue("expected dotted-quad IPv4, got '$ip'", ip.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")))
        assertFalse("must not report loopback", ip.startsWith("127."))
        assertFalse("must not report the wildcard", ip.startsWith("0."))
    }

    @Test
    fun `discovered IP is a real Inet4Address the JVM can parse`() {
        val ip = LocalIp.discover() ?: return
        val parsed = InetAddress.getByName(ip)
        assertNotNull(parsed)
        assertTrue("must be Inet4Address, got ${parsed.javaClass.simpleName}", parsed is Inet4Address)
        assertFalse(parsed.isLoopbackAddress)
        assertFalse(parsed.isAnyLocalAddress)
    }

    @Test
    fun `discovery is stable across successive calls`() {
        val a = LocalIp.discover()
        val b = LocalIp.discover()
        assumeTrue("no network on this test host", a != null && b != null)
        // The source address for the default route doesn't change between two immediate calls;
        // the legacy code path could return different addresses on a multi-interface host.
        assertTrue("discovery drifted between calls: $a vs $b", a == b)
    }
}
