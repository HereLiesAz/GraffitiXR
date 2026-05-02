package com.hereliesaz.graffitixr.common.wearable

import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertSame
import org.junit.Test

class WearableManagerSwapTest {

    private class FakeProvider(
        override val name: String = "Fake",
        override val capabilities: Set<GlassCapability> = setOf(GlassCapability.CAMERA_FEED),
        override val connectionState: MutableStateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Disconnected),
    ) : SmartGlassProvider {
        override fun connect() {
            connectionState.value = ConnectionState.Connected
        }
        override fun disconnect() {
            connectionState.value = ConnectionState.Disconnected
        }
    }

    @Test
    fun `activate swaps activeSensorSource to provider on Connected`() = runBlocking {
        val phoneSource = PhoneSensorSource()
        val provider = FakeProvider()
        val manager = WearableManager(setOf(provider), phoneSource)
        manager.activate(provider)
        val active = withTimeout(2_000) {
            manager.activeSensorSource.first { it === provider }
        }
        assertSame(provider, active)
    }

    @Test
    fun `disconnect rebinds activeSensorSource to phone`() = runBlocking {
        val phoneSource = PhoneSensorSource()
        val provider = FakeProvider()
        val manager = WearableManager(setOf(provider), phoneSource)
        manager.activate(provider)
        withTimeout(2_000) { manager.activeSensorSource.first { it === provider } }
        provider.connectionState.value = ConnectionState.Disconnected
        val active = withTimeout(2_000) {
            manager.activeSensorSource.first { it === phoneSource }
        }
        assertSame(phoneSource, active)
    }
}
