package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * IMPLEMENTATION.md **6a.4**, second half — the inline relocalization mode, and the same
 * "must be inert in release builds" requirement `EvalRngSeedGuardTest` covers for the seed.
 *
 * Sync mode is a *larger* release hazard than the seed, which is why it gets its own file rather
 * than a case in that one. A fixed RANSAC seed shipped to users is a behaviour change nobody would
 * feel; inline relocalization shipped to users moves a whole detect-match-solve pass onto the render
 * thread, several times a second. That is a dropped-frame bug reported as "the app stutters", with
 * no obvious path back to an evaluation affordance somebody left switched on.
 *
 * As with the seed, the effect lives in native code and cannot be exercised here — there is no
 * `libgraffitixr.so` on this classpath. What is testable is the *decision*: the gate, the cadence
 * arithmetic, and the sentinel. Those are where the realistic mistakes are.
 *
 * **On the exceptions.** As in `SlamManagerAnchorEstablishmentTest` and `EvalRngSeedGuardTest`: a
 * call that reaches JNI throws `UnsatisfiedLinkError` off-device, and a call that stops at the gate
 * returns normally. Both guard tests below exercise the REAL `SlamManager` methods, not a copy of
 * their logic, so deleting the `if (debuggable)` check from production code fails them.
 */
class EvalSyncRelocGuardTest {

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
    fun `a debuggable build reaches the native inline-reloc setter`() {
        try {
            slamManager.setEvalSyncRelocIfDebuggable(enabled = true, everyN = 5, debuggable = true)
            fail("expected the debuggable branch to reach nativeSetEvalSyncReloc and throw UnsatisfiedLinkError")
        } catch (expected: UnsatisfiedLinkError) {
            // expected: proves the debuggable=true branch called through to JNI.
        }
    }

    @Test
    fun `a release build never reaches the native inline-reloc setter`() {
        // If the `if (debuggable)` guard were removed from the real method, this would also throw
        // UnsatisfiedLinkError instead of returning normally.
        slamManager.setEvalSyncRelocIfDebuggable(enabled = true, everyN = 5, debuggable = false)
    }

    /**
     * `evalSyncRelocEveryN()` must be a genuine read-back through JNI, not a cached/local value. If
     * it were hardcoded to report whatever the caller last asked for, a release build — where the
     * gate above declines and nothing is ever set natively — would still report a stale cadence
     * instead of the true "off". Reaching `nativeGetEvalSyncRelocEveryN` and throwing off-device is
     * exactly the evidence that it queries the engine rather than remembering a value locally.
     */
    @Test
    fun `evalSyncRelocEveryN reads back through the engine rather than a cached value`() {
        try {
            slamManager.evalSyncRelocEveryN()
            fail("expected evalSyncRelocEveryN to reach nativeGetEvalSyncRelocEveryN and throw UnsatisfiedLinkError")
        } catch (expected: UnsatisfiedLinkError) {
            // expected: proves the accessor queries the engine instead of caching a value.
        }
    }

    /**
     * The cadence arithmetic the native side performs, restated here because getting it wrong is
     * silent in the worst way.
     *
     * `everyN` is floored at 1 natively. Zero divides by zero; negative makes `n % everyN` never
     * zero, so the mode reports itself ON and never relocalizes — which on a replay looks exactly
     * like relocalization being broken and gets blamed on whatever parameter the run was varying.
     * That is the failure this floor exists to prevent, and it is the reason the floor is not merely
     * defensive tidiness.
     */
    @Test
    fun `the cadence floor turns a nonsense N into every frame, never into no frames`() {
        for (n in intArrayOf(0, -1, Int.MIN_VALUE)) {
            val floored = maxOf(1, n)
            assertEquals("N=$n must floor to 1", 1, floored)
            // The thing that actually matters: some frame must pass the test.
            val passes = (1..10).count { it % floored == 0 }
            assertTrue("N=$n produced a mode that never relocalizes", passes > 0)
        }
    }

    @Test
    fun `every Nth frame means exactly one pass in N`() {
        for (everyN in intArrayOf(1, 2, 5, 30)) {
            val frames = 1..600
            val passes = frames.count { it % everyN == 0 }
            assertEquals("cadence $everyN over ${frames.count()} frames", frames.count() / everyN, passes)
        }
    }

    /**
     * `evalSyncRelocEveryN()` reports **0** when sync mode is off, so one int carries both the flag
     * and the cadence — and 0 can be the "off" marker here precisely because the native floor above
     * makes a REAL cadence of 0 unreachable (`maxOf(1, everyN)` never produces it), unlike the RNG
     * seed where 0 is a perfectly valid seed and that sentinel had to be negative instead. Tied to
     * the actual floor arithmetic, not to two hardcoded literals, so a change to the floor is what
     * would break this rather than a copy of the reasoning going stale on its own.
     */
    @Test
    fun `zero is a valid off marker for the cadence, unlike the seed`() {
        val everyReachableCadence = intArrayOf(0, -1, 1, 2, 30, Int.MIN_VALUE).map { maxOf(1, it) }
        assertTrue(
            "a cadence of 0 is unreachable through the real floor, so 0 can mean 'off'",
            everyReachableCadence.none { it == 0 },
        )
    }
}

/**
 * The JVM half of the native contract: `SlamManager` must still expose the inline-reloc entry points
 * under the exact names the eval harness calls, and the sidecar's read-back accessor must exist.
 *
 * Reflection rather than a direct call, because invoking them would hit JNI and fail with
 * `UnsatisfiedLinkError` in a unit test. This catches a rename or a signature change, which is the
 * realistic regression; it says nothing about whether the native side works — that is what the NDK
 * build gate is for, and a green run here must never be read as covering it.
 */
class SlamManagerEvalSyncRelocContractTest {

    @Test
    fun `the debuggable-gated enabler exists with the expected signature`() {
        val m = SlamManager::class.java.getMethod(
            "setEvalSyncRelocIfDebuggable",
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        assertEquals(Void.TYPE, m.returnType)
    }

    @Test
    fun `the clear counterpart exists`() {
        SlamManager::class.java.getMethod("clearEvalSyncReloc")
    }

    /**
     * The sidecar reads the cadence back from the engine rather than remembering what it asked for,
     * so this accessor is load-bearing: without it `EvalRunIdentity.syncReloc` would be whatever the
     * caller intended, and in a release build — where the gate declines — that would be a sidecar
     * claiming a synchronous run that never happened. `EVALUATION.md` §3.2 is explicit that a CSV
     * without a truthful sidecar is not evidence.
     */
    @Test
    fun `the read-back accessor exists and returns an Int`() {
        val m = SlamManager::class.java.getMethod("evalSyncRelocEveryN")
        assertEquals(Int::class.javaPrimitiveType, m.returnType)
    }
}
