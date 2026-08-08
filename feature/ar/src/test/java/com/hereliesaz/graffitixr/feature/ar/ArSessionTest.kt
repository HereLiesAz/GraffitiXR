package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
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
