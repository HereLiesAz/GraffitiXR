package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * IMPLEMENTATION.md **6a.4** — "Both must be inert in release builds."
 *
 * The seeding itself lives in native code and cannot be exercised from a JVM unit test (no
 * `libgraffitixr.so` on this classpath), so what is testable — and what actually carries the
 * requirement — is the *gate*: `setEvalRngSeedIfDebuggable` must not reach JNI when the build is
 * not debuggable.
 *
 * **On the exceptions.** `libgraffitixr.so` is not on a JVM unit-test classpath, so
 * `nativeSetEvalRngSeed` throws `UnsatisfiedLinkError` the moment it is actually invoked. That is
 * the instrument, not noise (see `SlamManagerAnchorEstablishmentTest`'s class KDoc for the same
 * technique): a call that stops at the gate returns normally, having never reached JNI, and a call
 * that passes the gate reaches `nativeSetEvalRngSeed` and throws. The two outcomes are unambiguous
 * evidence of which side of the `if (debuggable)` branch in `SlamManager.setEvalRngSeedIfDebuggable`
 * the call landed on — this exercises the REAL production method, not a copy of its logic, so
 * deleting the guard from that method fails these tests.
 */
class EvalRngSeedGuardTest {

    private lateinit var slamManager: SlamManager

    @Before
    fun setup() {
        // The constructor calls NativeLibLoader.loadAll(), which throws when the .so is absent.
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        slamManager = SlamManager(mockk<WearableManager>(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkObject(NativeLibLoader)
    }

    @Test
    fun `a debuggable build reaches the native seeder`() {
        try {
            slamManager.setEvalRngSeedIfDebuggable(20260801L, debuggable = true)
            fail("expected the debuggable branch to reach nativeSetEvalRngSeed and throw UnsatisfiedLinkError")
        } catch (expected: UnsatisfiedLinkError) {
            // expected: proves the debuggable=true branch called through to JNI.
        }
    }

    @Test
    fun `a release build never reaches the native seeder`() {
        // If the `if (debuggable)` guard were removed from the real method, this would also throw
        // UnsatisfiedLinkError instead of returning normally.
        slamManager.setEvalRngSeedIfDebuggable(20260801L, debuggable = false)
    }

    /**
     * A negative seed is the engine's "leave the RNG alone" sentinel. `clearEvalRngSeed` is not
     * itself debuggable-gated — unlike the seeder above, it always calls through — so reaching JNI
     * here is the expected, unconditional behaviour rather than evidence of a missing guard.
     */
    @Test
    fun `clearEvalRngSeed reaches the native seeder unconditionally`() {
        try {
            slamManager.clearEvalRngSeed()
            fail("expected clearEvalRngSeed to reach nativeSetEvalRngSeed and throw UnsatisfiedLinkError")
        } catch (expected: UnsatisfiedLinkError) {
            // expected: clearEvalRngSeed always calls through, regardless of debuggable state.
        }
    }

    /**
     * Zero is a valid RANSAC seed, not a "no seed" marker — only a NEGATIVE value means that. The
     * debuggable gate must not special-case zero as an implicit no-op; it must let it through like
     * any other seed once `debuggable` is true.
     */
    @Test
    fun `a zero seed in a debuggable build still reaches the native seeder`() {
        try {
            slamManager.setEvalRngSeedIfDebuggable(0L, debuggable = true)
            fail("expected a zero seed to reach nativeSetEvalRngSeed and throw UnsatisfiedLinkError")
        } catch (expected: UnsatisfiedLinkError) {
            // expected: zero is a real seed and must pass the gate exactly like any other.
        }
    }
}

/**
 * The JVM half of the native contract: `SlamManager` must still expose the seeding entry points
 * under the exact names `ArViewModel` and the eval harness call.
 *
 * Reflection rather than a direct call, because invoking them would hit `nativeSetEvalRngSeed` and
 * fail with `UnsatisfiedLinkError` in a unit test. This catches a rename or a signature change,
 * which is the realistic regression; it says nothing about whether the native side works — that is
 * what the NDK build gate is for, and a green run here must never be read as covering it.
 */
class SlamManagerEvalSeedContractTest {

    @Test
    fun `the debuggable-gated seeder exists with the expected signature`() {
        val m = SlamManager::class.java.getMethod(
            "setEvalRngSeedIfDebuggable", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        assertEquals(Void.TYPE, m.returnType)
    }

    @Test
    fun `the raw seeder and its clear counterpart exist`() {
        SlamManager::class.java.getMethod("setEvalRngSeed", Long::class.javaPrimitiveType)
        SlamManager::class.java.getMethod("clearEvalRngSeed")
    }
}
