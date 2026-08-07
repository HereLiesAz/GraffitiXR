package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.lang.reflect.Modifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The previous version of this file mocked [SlamManager] itself and asserted that the mock
 * recorded a call:
 * ```
 * private val slamManager: SlamManager = mockk(relaxed = true)
 * slamManager.loadSuperPoint(assets)
 * verify { slamManager.loadSuperPoint(assets) }
 * ```
 * That proves mockk can record a call on a mock. It proves nothing about the real
 * `loadSuperPoint`/`nativeLoadSuperPoint`/`SuperPointDetector::load` path — a version that renamed,
 * broke, or stubbed the real chain to just `return true` would still pass, since nothing in that
 * test ever touched the real class.
 *
 * `libgraffitixr.so` is not on a JVM unit-test classpath (see `EvalRngSeedGuardTest`'s and
 * `SlamManagerAnchorEstablishmentTest`'s KDocs for the same constraint), so `SuperPointDetector::load`
 * itself cannot run here — that is what the NDK-backed instrumented/eval harness is for. What CAN be
 * exercised honestly from a plain JVM test, following `SlamManagerAnchorEstablishmentTest`'s pattern
 * exactly:
 *
 *  - Mock only `NativeLibLoader` (whose `loadAll()` throws off-device and would otherwise fail
 *    construction before this test gets to say anything), so a REAL, unmocked [SlamManager] can be
 *    constructed.
 *  - Call the REAL [SlamManager.loadSuperPoint] / [SlamManager.loadLowLightEnhancer] and require
 *    `UnsatisfiedLinkError` — the JVM's evidence that it genuinely tried and failed to resolve
 *    `nativeLoadSuperPoint` / `nativeLoadLowLightEnhancer` as native symbols. That error can ONLY be
 *    thrown by an actual attempt to invoke a native method; a Kotlin-side stub, a swallowed
 *    exception, or a renamed/deleted delegate would all make these tests fail instead of silently
 *    passing the way the mock-based version did.
 *
 * This bounds what these tests can catch, and the bound is worth stating: they prove the Kotlin
 * side still reaches for the correct native symbol. They say nothing about what
 * `SuperPointDetector::load` does once that symbol resolves on a real device — only an instrumented
 * run closes that gap.
 */
class SlamManagerModelLoadingTest {

    private lateinit var slamManager: SlamManager

    @Before
    fun setup() {
        // The constructor calls NativeLibLoader.loadAll(), which throws when the .so is absent.
        // Mocked away so this test isolates loadSuperPoint/loadLowLightEnhancer's own reach to JNI,
        // exactly as SlamManagerAnchorEstablishmentTest does for the anchor-establishment gate.
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        slamManager = SlamManager(mockk<WearableManager>(relaxed = true))
    }

    @After
    fun tearDown() {
        unmockkObject(NativeLibLoader)
    }

    @Test
    fun `loadSuperPoint reaches real native code, not a Kotlin-side stub`() {
        val assets: AssetManager = mockk(relaxed = true)
        try {
            slamManager.loadSuperPoint(assets)
            fail(
                "expected nativeLoadSuperPoint to be unresolvable off-device " +
                    "(UnsatisfiedLinkError) -- a normal return here means loadSuperPoint no longer " +
                    "reaches JNI at all, which is exactly the regression a mock of SlamManager " +
                    "itself could never catch",
            )
        } catch (expected: UnsatisfiedLinkError) {
            // Reaching JNI and failing to resolve the symbol is the expected, honest outcome
            // off-device; see class KDoc / SlamManagerAnchorEstablishmentTest.
        }
    }

    @Test
    fun `loadLowLightEnhancer reaches real native code, not a Kotlin-side stub`() {
        val assets: AssetManager = mockk(relaxed = true)
        try {
            slamManager.loadLowLightEnhancer(assets)
            fail(
                "expected nativeLoadLowLightEnhancer to be unresolvable off-device " +
                    "(UnsatisfiedLinkError) -- a normal return here means loadLowLightEnhancer no " +
                    "longer reaches JNI at all, which is exactly the regression a mock of " +
                    "SlamManager itself could never catch",
            )
        } catch (expected: UnsatisfiedLinkError) {
            // Reaching JNI and failing to resolve the symbol is the expected, honest outcome
            // off-device; see class KDoc / SlamManagerAnchorEstablishmentTest.
        }
    }

    /**
     * Signature lock, over the REAL class via reflection (mirrors `YuvConverterContractTest` /
     * `SlamManagerEvalSeedContractTest`): catches a rename or arity change that the two tests above
     * would also catch, but with a message that says exactly what changed instead of an opaque
     * `NoSuchMethodError` surfacing from `setup()`.
     */
    @Test
    fun `loadSuperPoint and loadLowLightEnhancer keep their public signature`() {
        val loadSuperPoint = SlamManager::class.java.getMethod("loadSuperPoint", AssetManager::class.java)
        assertEquals(Boolean::class.javaPrimitiveType, loadSuperPoint.returnType)

        val loadEnhancer = SlamManager::class.java.getMethod("loadLowLightEnhancer", AssetManager::class.java)
        assertEquals(Void.TYPE, loadEnhancer.returnType)

        val nativeLoadSuperPoint =
            SlamManager::class.java.getDeclaredMethod("nativeLoadSuperPoint", AssetManager::class.java)
        assertTrue(
            "nativeLoadSuperPoint must be declared external (JNI-backed)",
            Modifier.isNative(nativeLoadSuperPoint.modifiers),
        )

        val nativeLoadEnhancer =
            SlamManager::class.java.getDeclaredMethod("nativeLoadLowLightEnhancer", AssetManager::class.java)
        assertTrue(
            "nativeLoadLowLightEnhancer must be declared external (JNI-backed)",
            Modifier.isNative(nativeLoadEnhancer.modifiers),
        )
    }
}
