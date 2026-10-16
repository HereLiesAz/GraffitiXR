package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The re-capture gate: between the anchor being replaced and the NEW fingerprint + NEW capture pose
 * being installed, reloc results describe the previous wall and must not be used.
 *
 * Only the Kotlin state machine is testable here (no `libgraffitixr.so` on the JVM classpath); the
 * native generation counter has its own host tests, and the renderer/arbiter consume this flag.
 */
class SlamManagerFingerprintReplacementTest {
    private lateinit var slamManager: SlamManager

    @Before
    fun setup() {
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        slamManager = SlamManager(mockk<WearableManager>(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkObject(NativeLibLoader)
    }

    @Test
    fun `no gate until a re-capture begins`() {
        assertFalse(slamManager.awaitingNewFingerprint)
    }

    @Test
    fun `beginning a replacement drops the old pose and raises the gate`() {
        slamManager.captureAnchorCam = FloatArray(16) { 1f }
        slamManager.beginFingerprintReplacement()
        assertTrue(slamManager.awaitingNewFingerprint)
        assertNull(slamManager.captureAnchorCam)
    }

    @Test
    fun `the gate stays up until the new pose is installed, and the pose lands first`() {
        slamManager.beginFingerprintReplacement()
        // Nothing installed yet: still gated, still no pose.
        assertTrue(slamManager.awaitingNewFingerprint)
        assertNull(slamManager.captureAnchorCam)

        val fresh = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        slamManager.completeFingerprintReplacement(fresh)
        assertFalse(slamManager.awaitingNewFingerprint)
        assertArrayEquals(fresh, slamManager.captureAnchorCam, 0f)
    }

    @Test
    fun `a fingerprint with no capture pose still lowers the gate and leaves reloc uncorrected`() {
        slamManager.beginFingerprintReplacement()
        slamManager.completeFingerprintReplacement(null)
        assertFalse(slamManager.awaitingNewFingerprint)
        assertNull(slamManager.captureAnchorCam)
    }
}
