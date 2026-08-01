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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `awaitAnchorTransform` must wait for an establishment NEWER than the caller's snapshot — not for
 * any anchor write, and not merely for "one has ever happened".
 *
 * Two cuts of this fix were wrong in two different ways, and both are pinned below.
 *
 * The first version of this fix set the flag inside `updateAnchorTransform`. That method is what
 * `refineAnchorFromBestPlane` calls every 30 frames *while the anchor is still unestablished*, so
 * the flag flipped within the first second of scanning and the capture never waited at all — it
 * read the plane refiner's provisional pose. The refiner builds its basis with the wall normal in
 * **Z**; establishment takes ARCore's `hitPose`, whose local **+Y** is the normal. Reading one where
 * the other is meant is a ~90° frame error by construction, and it shipped as a fix.
 *
 * The second used a boolean latch. Every target confirmation RE-establishes the anchor, so after the
 * first capture the latch is permanently true and every later capture resolves instantly — against
 * the previous anchor's pose, one GL frame before the new one is written. Re-capture is not exotic;
 * the app's own legacy-fingerprint message tells the artist to create the target again. Hence a
 * generation counter, and hence the last test here.
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

        assertEquals(
            "a pose write must not count as establishment",
            0, slamManager.anchorGeneration.value,
        )
        // Null means the wait ran its full budget and gave up. Had the write opened the gate this
        // would have reached nativeGetAnchorTransform and thrown instead.
        assertNull(slamManager.awaitAnchorTransform(sinceGeneration = 0, timeoutMs = 5_000L))
    }

    @Test
    fun `awaitAnchorTransform completes only once the anchor is established`() = runTest {
        var outcome: Result<FloatArray?>? = null
        val waiter = launch {
            outcome = runCatching {
                slamManager.awaitAnchorTransform(sinceGeneration = 0, timeoutMs = 60_000L)
            }
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

    /**
     * **The session boundary**, and the case the monotonic counter opened up.
     *
     * Resetting the counter to zero on teardown stranded in-flight waits, so it became monotonic —
     * which fixed that and silently turned a fail-CLOSED outcome into a fail-OPEN one: teardown then
     * re-entry yields a generation strictly greater than the waiter's baseline with an anchor
     * present again, so the wait resolved and handed the NEW session's anchor to the OLD session's
     * capture. That capture back-projects its pixels through a foreign anchor and persists a
     * structurally valid, geometrically meaningless target.
     *
     * Refusing is the contract (`PARAMETERS.md` §6: "timing out REFUSES rather than falling back").
     */
    @Test
    fun `an anchor from a later session cannot satisfy an earlier session's wait`() = runTest {
        slamManager.markAnchorEstablished()
        val baseline = slamManager.anchorGeneration.value

        var outcome: Result<FloatArray?>? = null
        val waiter = launch {
            outcome = runCatching { slamManager.awaitAnchorTransform(baseline, timeoutMs = 60_000L) }
        }
        testScheduler.runCurrent()
        assertFalse(waiter.isCompleted)

        slamManager.clearAnchorEstablished()  // AR teardown
        slamManager.markAnchorEstablished()   // a new session establishes its own anchor
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertTrue("the wait must end rather than hang", waiter.isCompleted)
        assertNull(
            "a later session's anchor answers a different question; the capture must be refused",
            outcome?.getOrNull(),
        )
        assertFalse(
            "and it must refuse by returning null, not by reaching JNI; got $outcome",
            outcome?.exceptionOrNull() is UnsatisfiedLinkError,
        )
    }

    @Test
    fun `clearAnchorEstablished makes the next capture wait again`() = runTest {
        slamManager.markAnchorEstablished()
        assertEquals(1, slamManager.anchorGeneration.value)

        // AR teardown: the ARCore session and its anchors are gone, so an establishment from the old
        // session must not satisfy a wait in the new one.
        slamManager.clearAnchorEstablished()

        assertNull(slamManager.awaitAnchorTransform(sinceGeneration = 0, timeoutMs = 5_000L))
    }

    /**
     * The re-capture case, and the one a boolean latch cannot express.
     *
     * A second capture snapshots the generation AFTER the first capture established an anchor, so
     * "has an anchor ever been established" is already true and tells it nothing. It must keep
     * waiting until the count advances past its own snapshot — otherwise it reads the pose of the
     * anchor it is about to replace, and the fingerprint is co-registered to a dead frame.
     */
    @Test
    fun `a second capture waits for an establishment newer than its own snapshot`() = runTest {
        slamManager.markAnchorEstablished() // capture #1 establishes anchor #1
        val snapshotBeforeSecondCapture = slamManager.anchorGeneration.value
        assertEquals(1, snapshotBeforeSecondCapture)

        var outcome: Result<FloatArray?>? = null
        val waiter = launch {
            outcome = runCatching {
                slamManager.awaitAnchorTransform(snapshotBeforeSecondCapture, timeoutMs = 60_000L)
            }
        }
        testScheduler.runCurrent()
        assertFalse(
            "an already-established anchor must not satisfy a later capture's wait",
            waiter.isCompleted,
        )

        // A provisional write in between must not release it either.
        writeProvisionalPose()
        testScheduler.runCurrent()
        assertFalse("a pose write is still not an establishment", waiter.isCompleted)

        slamManager.markAnchorEstablished() // capture #2 establishes anchor #2
        testScheduler.runCurrent()

        assertTrue("the NEW establishment must release the wait", waiter.isCompleted)
        assertTrue(
            "the wait must have gone on to read the pose, not timed out; got $outcome",
            outcome?.exceptionOrNull() is UnsatisfiedLinkError,
        )
    }
}
