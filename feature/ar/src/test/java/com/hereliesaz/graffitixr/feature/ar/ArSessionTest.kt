package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.SessionLifecycleOutcome
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import com.hereliesaz.graffitixr.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ArSessionTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager = mockk(relaxed = true)
    private val collaborationManager: com.hereliesaz.graffitixr.core.collaboration.CollaborationManager = mockk(relaxed = true)
    private val wearableManager: WearableManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        every { settingsRepository.ambientScanEnabled } returns flowOf(true)
        every { settingsRepository.isRightHanded } returns flowOf(true)
        every { settingsRepository.showAnchorBoundary } returns flowOf(false)
        every { projectRepository.currentProject } returns MutableStateFlow(null)
        every { context.filesDir } returns File("/tmp")
        viewModel = ArViewModel(slamManager, projectRepository, settingsRepository, projectManager, collaborationManager, wearableManager, context, testDispatchers)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(NativeLibLoader)
    }

    // `resumeArSessionInternal`/`pauseArSessionInternal` cannot be exercised through the public
    // `setArMode(true, ...)` entry point in a JVM unit test: it constructs a real ARCore `Session`,
    // which throws `UnsatisfiedLinkError` off-device (see the ignored test this file used to have).
    // Instead these tests reach the private lifecycle methods directly via reflection with a mocked
    // `Session` injected into the private `session` field — the same technique
    // `ArViewModelTest.setPrivateField`/`getPrivateField` use — so the assertion lands on the ARCORE
    // CALL ITSELF (`Session.resume()`/`Session.pause()`), not just "no exception was thrown".
    //
    // `renderer` is left null in these tests: `ArRenderer` cannot be substituted with a mock in this
    // harness (see `ArViewModelTest`'s note on `attaching re-applies the self-grow intent` — a relaxed
    // mockk does not intercept its methods, so any call into one dereferences real, uninitialised
    // fields such as its ReentrantLock). With `renderer == null`,
    // `resumeArSessionInternal`/`pauseArSessionInternal` take the direct-session-call fallback path —
    // the one this harness can actually observe — which is exactly right, because with no renderer
    // there is no GL thread that could be racing the session either.
    //
    // That leaves the `SessionLifecycleOutcome.LockTimeout` branch (renderer present, GL thread still
    // wedged past the bounded wait) permanently unreachable from here — PR #1831 shipped it with zero
    // coverage because of exactly this mocking gap. Rather than fight the harness to get a fake
    // `ArRenderer` in, the retry/give-up decision that branch now runs was pulled out into
    // `retryOnLockTimeout`, a plain top-level function that takes the renderer call as a lambda — see
    // the tests below, which exercise it directly with zero ARCore/ArRenderer involved at all.

    @Test
    fun `resumeArSessionInternal resumes the session and flips isSessionResumed`() {
        val mockSession: Session = mockk(relaxed = true)
        setPrivateField(viewModel, "session", mockSession)

        invokePrivate(viewModel, "resumeArSessionInternal")

        verify(exactly = 1) { mockSession.resume() }
        assertTrue(getPrivateField(viewModel, "isSessionResumed") as Boolean)
    }

    @Test
    fun `resumeArSessionInternal is a no-op with no session`() {
        // session is null by default — resume() must never be reached, and isSessionResumed must
        // stay false rather than being flipped on nothing.
        invokePrivate(viewModel, "resumeArSessionInternal")
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)
    }

    @Test
    fun `pauseArSessionInternal pauses the session and clears isSessionResumed`() {
        val mockSession: Session = mockk(relaxed = true)
        setPrivateField(viewModel, "session", mockSession)
        setPrivateField(viewModel, "isSessionResumed", true)

        invokePrivate(viewModel, "pauseArSessionInternal")

        verify(exactly = 1) { mockSession.pause() }
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)
    }

    /**
     * `updateSessionStateLocked` (driven here through the public `onActivityResumed()`) must not
     * reach `resumeArSessionInternal` at all while `isInArMode` is false — verified on the mock
     * session itself rather than inferred from "nothing crashed".
     */
    @Test
    fun `session should not resume if not in AR mode`() = runTest {
        val mockSession: Session = mockk(relaxed = true)
        setPrivateField(viewModel, "session", mockSession)

        viewModel.setArMode(false, context)
        viewModel.onActivityResumed()
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { mockSession.resume() }
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)
    }

    @Test
    fun `destroyArSession should not crash if session is null`() = runTest {
        // session is null by default
        viewModel.destroyArSession()
        testDispatcher.scheduler.advanceUntilIdle()

        // The only observable effect of a no-session cleanup: the destroying latch
        // (performFullCleanupLocked's own `isDestroying = true` at its top) must be released again,
        // not left stuck true — which would otherwise wedge every future onDrawFrame's isDestroying
        // check into early-returning forever.
        assertFalse(getPrivateField(viewModel, "isDestroying") as Boolean)
    }

    // --- retryOnLockTimeout (finding #2 / #8) ---
    // The LockTimeout branch previously just logged and gave up on the very first bounded-lock
    // timeout, with no test able to construct that outcome (see the note above). These assert the
    // fixed behavior directly against the extracted decision function: retry a bounded number of
    // times, sleeping between attempts, and stop retrying as soon as a non-LockTimeout outcome (or the
    // retry cap) is reached.

    @Test
    fun `retryOnLockTimeout returns the first outcome without retrying when it is not a LockTimeout`() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val outcome = retryOnLockTimeout(maxRetries = 3, delayMs = 50L, sleeper = { sleeps.add(it) }) {
            calls++
            SessionLifecycleOutcome.Applied
        }
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
        assertTrue(outcome is SessionLifecycleOutcome.Applied)
    }

    @Test
    fun `retryOnLockTimeout retries a persistent LockTimeout up to maxRetries then gives up`() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val outcome = retryOnLockTimeout(maxRetries = 3, delayMs = 50L, sleeper = { sleeps.add(it) }) {
            calls++
            SessionLifecycleOutcome.LockTimeout
        }
        // Initial attempt + 3 retries = 4 total calls, with a sleep before each retry.
        assertEquals(4, calls)
        assertEquals(listOf(50L, 50L, 50L), sleeps)
        assertTrue(outcome is SessionLifecycleOutcome.LockTimeout)
    }

    @Test
    fun `retryOnLockTimeout stops retrying as soon as a later attempt succeeds`() {
        var calls = 0
        val outcome = retryOnLockTimeout(maxRetries = 3, delayMs = 10L, sleeper = {}) {
            calls++
            if (calls < 3) SessionLifecycleOutcome.LockTimeout else SessionLifecycleOutcome.Applied
        }
        // Two LockTimeouts, then Applied on the third attempt — must not burn the remaining retry.
        assertEquals(3, calls)
        assertTrue(outcome is SessionLifecycleOutcome.Applied)
    }

    @Test
    fun `retryOnLockTimeout with zero maxRetries never retries`() {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val outcome = retryOnLockTimeout(maxRetries = 0, delayMs = 50L, sleeper = { sleeps.add(it) }) {
            calls++
            SessionLifecycleOutcome.LockTimeout
        }
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
        assertTrue(outcome is SessionLifecycleOutcome.LockTimeout)
    }

    private fun setPrivateField(obj: Any, fieldName: String, value: Any?) {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun getPrivateField(obj: Any, fieldName: String): Any? {
        val field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }

    private fun invokePrivate(obj: Any, methodName: String) {
        val method = obj.javaClass.getDeclaredMethod(methodName)
        method.isAccessible = true
        method.invoke(obj)
    }
}
