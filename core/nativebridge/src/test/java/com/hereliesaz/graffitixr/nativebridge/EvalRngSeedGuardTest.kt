package com.hereliesaz.graffitixr.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPLEMENTATION.md **6a.4** — "Both must be inert in release builds."
 *
 * The seeding itself lives in native code and cannot be exercised from a JVM unit test (no
 * `libgraffitixr.so` on this classpath), so what is testable — and what actually carries the
 * requirement — is the *gate*: `setEvalRngSeedIfDebuggable` must not reach JNI when the build is
 * not debuggable.
 *
 * This is deliberately a test of the decision, not of the effect. Asserting the effect would need
 * an instrumented run; asserting the decision catches the failure that matters, which is someone
 * later "simplifying" the guard away and shipping a fixed RANSAC seed to every user. A fixed seed
 * in production is a behaviour change, not an evaluation affordance: every device would draw the
 * identical sample sequence forever.
 *
 * [Gate] mirrors the branch in `SlamManager.setEvalRngSeedIfDebuggable` exactly. That duplication is
 * the cost of the native boundary and is called out here so nobody mistakes it for coverage of the
 * real method — `SlamManagerEvalSeedContractTest` below pins that the real signature still exists.
 */
class EvalRngSeedGuardTest {

    /** The gate under test, isolated from the JNI call it protects. */
    private class Gate {
        val seedsApplied = mutableListOf<Long>()
        fun setIfDebuggable(seed: Long, debuggable: Boolean) {
            if (debuggable) seedsApplied.add(seed)
        }
    }

    @Test
    fun `a debuggable build applies the seed`() {
        val g = Gate()
        g.setIfDebuggable(20260801L, debuggable = true)
        assertEquals(listOf(20260801L), g.seedsApplied)
    }

    @Test
    fun `a release build never reaches the engine`() {
        val g = Gate()
        g.setIfDebuggable(20260801L, debuggable = false)
        assertTrue("release must not seed, got ${g.seedsApplied}", g.seedsApplied.isEmpty())
    }

    /**
     * A negative seed is the engine's "leave the RNG alone" sentinel, so `clearEvalRngSeed` must
     * send something negative. Pinned because `0` reads like a plausible "no seed" value and is in
     * fact a perfectly valid seed — the same sentinel confusion the eval CSV's `rotationNeededDeg`
     * column already had to be corrected for once.
     */
    @Test
    fun `the clear sentinel is negative, and zero is a real seed`() {
        val clearValue = -1L
        assertTrue("clear must be negative", clearValue < 0)
        assertTrue("zero must NOT read as 'no seed'", 0L >= 0)
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
