package com.hereliesaz.graffitixr.nativebridge

import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `awaitAnchorTransform` must wait for the anchor to be ESTABLISHED, not for any anchor write.
 *
 * The first version of this fix set the flag inside `updateAnchorTransform`. That method is what
 * `refineAnchorFromBestPlane` calls every 30 frames *while the anchor is still unestablished*, so
 * the flag flipped within the first second of scanning and the capture never waited at all — it
 * read the plane refiner's provisional pose. The refiner builds its basis with the wall normal in
 * **Z**; establishment takes ARCore's `hitPose`, whose local **+Y** is the normal. Reading one where
 * the other is meant is a ~90° frame error by construction, and it shipped as a fix. Hence these
 * tests pin the gate itself, not merely that a pose eventually comes back.
 *
 * **On the exceptions.** `libgraffitixr.so` is not on a JVM unit-test classpath, so every `external
 * fun` throws `UnsatisfiedLinkError`. That is not noise here, it is the instrument: a call that is
 * still waiting on the gate returns null at its timeout without ever reaching JNI, and a call that
 * has passed the gate reaches `nativeGetAnchorTransform` and throws. The two outcomes are therefore
 * unambiguous evidence of which side of the gate the coroutine is on.
 *
 * It also bounds what these tests can catch, and the bound is worth stating rather than discovering:
 * a regression that set the flag *after* `nativeUpdateAnchorTransform` would be invisible here,
 * because the throw gets there first. Off-device that is the whole method; on-device it is one line
 * of it. Only an instrumented run closes that gap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlamManagerAnchorEstablishmentTest {

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

    /** Stand-in for what the plane refiner pushes every 30 frames before the anchor exists. */
    private fun provisionalPlanePose() = FloatArray(16) { if (it % 5 == 0) 1f else 0f }

    /**
     * The exact call the refiner makes, taken through the real public method so the gate under test
     * is the production one. The JNI hop it ends in cannot resolve here; what matters is whether the
     * method touched the establishment flag on its way there.
     */
    private fun writeProvisionalPose() {
        try {
            slamManager.updateAnchorTransform(provisionalPlanePose())
        } catch (expected: UnsatisfiedLinkError) {
            // nativeUpdateAnchorTransform is unresolvable off-device; see the class KDoc.
        }
    }

    @Test
    fun `a provisional pose write leaves a waiting capture waiting`() = runTest {
        writeProvisionalPose()

        assertFalse(
            "a pose write must not count as establishment",
            slamManager.anchorEstablished.value,
        )
        // Null means the wait ran its full budget and gave up. Had the write opened the gate this
        // would have reached nativeGetAnchorTransform and thrown instead.
        assertNull(slamManager.awaitAnchorTransform(timeoutMs = 5_000L))
    }

    @Test
    fun `awaitAnchorTransform completes only once the anchor is established`() = runTest {
        var outcome: Result<FloatArray?>? = null
        val waiter = launch {
            outcome = runCatching { slamManager.awaitAnchorTransform(timeoutMs = 60_000L) }
        }
        testScheduler.runCurrent()
        assertFalse("must not resolve with no anchor at all", waiter.isCompleted)

        writeProvisionalPose()
        testScheduler.runCurrent()
        assertFalse("must not resolve on the plane refiner's pose", waiter.isCompleted)

        slamManager.markAnchorEstablished()
        testScheduler.runCurrent()

        assertTrue("establishment must release the wait", waiter.isCompleted)
        assertTrue(
            "the wait must have gone on to read the pose, not timed out; got $outcome",
            outcome?.exceptionOrNull() is UnsatisfiedLinkError,
        )
    }

    @Test
    fun `clearAnchorEstablished makes the next capture wait again`() = runTest {
        slamManager.markAnchorEstablished()
        assertTrue(slamManager.anchorEstablished.value)

        // AR teardown: the ARCore session and its anchors are gone, so "established" must not
        // survive, or the next capture resolves instantly against the previous session's pose.
        slamManager.clearAnchorEstablished()

        assertNull(slamManager.awaitAnchorTransform(timeoutMs = 5_000L))
    }
}
