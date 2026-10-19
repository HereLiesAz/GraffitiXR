package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.FeedbackEvent
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.sphereslam.SphereSlam
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ARCore session lifecycle: entry interrupted by pause/resume, teardown after the ViewModel's scope
 * is gone, teardown racing the next renderer, saves that must finish before close, and the
 * settings/scan state that must survive (or be reset by) AR exit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelArCoreLifecycleTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager = mockk(relaxed = true)
    private val collaborationManager: com.hereliesaz.graffitixr.core.collaboration.CollaborationManager =
        mockk(relaxed = true) {
        // A relaxed mock's StateFlow.collect returns Unit where Nothing is required, so the tripod
        // collectors in ArViewModel.init would throw; give them real flows.
        io.mockk.every { videoWanted } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        io.mockk.every { video } returns kotlinx.coroutines.flow.MutableStateFlow<ByteArray?>(null)
    }
    private val wearableManager: WearableManager = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }
    private val currentProject = MutableStateFlow<GraffitiProject?>(null)
    private var viewModel: ArViewModel? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        mockkObject(SphereSlam)
        every { SphereSlam.isOperational() } returns true
        mockkObject(ArAvailabilityChecker)
        coEvery { ArAvailabilityChecker.check(any()) } returns ArAvailabilityChecker.Result.Supported
        every { settingsRepository.ambientScanEnabled } returns flowOf(true)
        // Pinned off: these lifecycle tests are about teardown ordering, not the shipped defaults.
        every { settingsRepository.driftCorrectionEnabled } returns flowOf(false)
        every { settingsRepository.selfGrowEnabled } returns flowOf(false)
        every { settingsRepository.featureMapEnabled } returns flowOf(false)
        every { settingsRepository.isRightHanded } returns flowOf(true)
        every { settingsRepository.showAnchorBoundary } returns flowOf(false)
        every { settingsRepository.isImperialUnits } returns flowOf(false)
        every { settingsRepository.monocularDepthEnabled } returns flowOf(false)
        every { settingsRepository.arCoreEnabled } returns flowOf(true)
        every { settingsRepository.arCoreDepthEnabled } returns flowOf(true)
        every { settingsRepository.adaptiveRateEnabled } returns flowOf(true)
        every { settingsRepository.autoFocusEnabled } returns flowOf(true)
        every { projectRepository.currentProject } returns currentProject
        every { appContext.filesDir } returns File("/tmp")
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        testDispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        unmockkObject(ArAvailabilityChecker)
        unmockkObject(SphereSlam)
        unmockkObject(NativeLibLoader)
    }

    private fun newViewModel(): ArViewModel =
        ArViewModel(
            slamManager, projectRepository, settingsRepository, projectManager,
            collaborationManager, wearableManager, appContext, testDispatchers,
        ).also {
            viewModel = it
            testDispatcher.scheduler.advanceUntilIdle()
        }

    /** A camera service whose back camera never reports "available" — the gate waits it out. */
    private fun busyCameraManager(): CameraManager {
        val chars: CameraCharacteristics = mockk(relaxed = true)
        every { chars.get<Int>(any()) } returns CameraCharacteristics.LENS_FACING_BACK
        return mockk<CameraManager>(relaxed = true).also { cm ->
            every { cm.cameraIdList } returns arrayOf("0")
            every { cm.getCameraCharacteristics("0") } returns chars
        }
    }

    @Test
    fun `pause during the camera gate is not a camera fault and resume restarts entry without a context`() {
        val vm = newViewModel()
        val activityContext: Context = mockk(relaxed = true)
        every { activityContext.getSystemService(Context.CAMERA_SERVICE) } returns busyCameraManager()
        every { appContext.getSystemService(Context.CAMERA_SERVICE) } returns busyCameraManager()
        val events = mutableListOf<FeedbackEvent>()
        val collector = CoroutineScope(testDispatcher).launch { vm.feedback.collect { events += it } }

        vm.onActivityResumed()
        vm.setArMode(true, activityContext)
        testDispatcher.scheduler.runCurrent() // inside the camera gate, waiting for availability
        verify { activityContext.getSystemService(Context.CAMERA_SERVICE) }

        // Background the app mid-entry: cancels the entry job. That cancellation must not surface as
        // "Camera is busy" (which also used to arm the user's retry path for a non-fault).
        vm.onActivityPaused()
        testDispatcher.scheduler.runCurrent()
        assertTrue(
            "cancellation reported as a camera fault: $events",
            events.none { it is FeedbackEvent.Error },
        )

        // Foreground again: the lifecycle caller has no Context, but entry must resume (via the
        // application context) instead of silently leaving AR without a session.
        vm.onActivityResumed()
        testDispatcher.scheduler.runCurrent()
        verify { appContext.getSystemService(Context.CAMERA_SERVICE) }

        vm.onActivityPaused()
        testDispatcher.scheduler.runCurrent()
        collector.cancel()
    }

    @Test
    fun `onCleared closes the session even though viewModelScope is already cancelled`() {
        val vm = newViewModel()
        val session: Session = mockk(relaxed = true)
        setPrivateField(vm, "session", session)
        setPrivateField(vm, "isSessionResumed", true)

        // The framework cancels viewModelScope BEFORE calling onCleared.
        vm.viewModelScope.cancel()
        invokeOnCleared(vm)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { session.pause() }
        verify(exactly = 1) { session.close() }
        assertNull(getPrivateField(vm, "session"))
        assertFalse(getPrivateField(vm, "isDestroying") as Boolean)
    }

    @Test
    fun `teardown does not null a renderer attached after it began`() {
        val vm = newViewModel()
        val session: Session = mockk(relaxed = true)
        setPrivateField(vm, "session", session)

        // AR exit with no renderer attached yet, then the next entry's renderer arrives before the
        // teardown has taken the session mutex.
        val job = vm.exitArMode()
        val nextRenderer: ArRenderer = mockk(relaxed = true)
        setPrivateField(vm, "renderer", nextRenderer)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(job.isCompleted)
        verify(exactly = 1) { session.close() }
        assertNull(getPrivateField(vm, "session"))
        assertSame(nextRenderer, getPrivateField(vm, "renderer"))
    }

    @Test
    fun `AR exit finishes the map save before the session is closed`() {
        val vm = newViewModel()
        currentProject.value = GraffitiProject(id = "p1")
        testDispatcher.scheduler.advanceUntilIdle()
        // The doodle loader claims native-state ownership on the real IO dispatcher, which
        // advanceUntilIdle cannot wait for, and the save is (rightly) skipped until it has. Exiting
        // before that made this test a race.
        awaitNativeOwnership(vm, "p1")
        val session: Session = mockk(relaxed = true)
        setPrivateField(vm, "session", session)

        vm.exitArMode()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>())
            session.close()
        }
    }

    @Test
    fun `switching the ambient sweep back on restarts the scan`() {
        val vm = newViewModel()
        completeScan(vm)

        vm.setAmbientScanEnabled(false)
        vm.setAmbientScanEnabled(true)

        assertEquals(ScanPhase.AMBIENT, vm.uiState.value.scanPhase)
        assertEquals(0L, vm.uiState.value.visitedSectorsMask)
        assertTrue((getPrivateField(vm, "visitedSectors") as BooleanArray).none { it })
    }

    @Test
    fun `AR exit resets the scan progression`() {
        val vm = newViewModel()
        completeScan(vm)

        vm.exitArMode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ScanPhase.AMBIENT, vm.uiState.value.scanPhase)
        assertTrue((getPrivateField(vm, "visitedSectors") as BooleanArray).none { it })
    }

    @Test
    fun `anchor boundary toggle is persisted`() {
        val vm = newViewModel()
        vm.setShowAnchorBoundary(true)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.showAnchorBoundary)
        coVerify { settingsRepository.setShowAnchorBoundary(true) }
    }

    @Test
    fun `a saved FIXED focus survives AR exit in the UI state`() {
        every { settingsRepository.autoFocusEnabled } returns flowOf(false)
        val vm = newViewModel()
        assertFalse(vm.evalAutoFocusEnabled.value)

        vm.exitArMode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.evalAutoFocusEnabled.value)
        coVerify(exactly = 0) { settingsRepository.setAutoFocusEnabled(any()) }
    }

    private fun completeScan(vm: ArViewModel) {
        (getPrivateField(vm, "visitedSectors") as BooleanArray).fill(true)
        @Suppress("UNCHECKED_CAST")
        val state = getPrivateField(vm, "_uiState") as
            MutableStateFlow<com.hereliesaz.graffitixr.common.model.ArUiState>
        state.update { it.copy(scanPhase = ScanPhase.COMPLETE, visitedSectorsMask = (1L shl 36) - 1) }
    }

    private fun invokeOnCleared(vm: ArViewModel) {
        val m = ArViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        m.invoke(vm)
    }

    /** Wait (real time, bounded) for the IO-dispatched doodle loader to claim native state for [projectId]. */
    private fun awaitNativeOwnership(vm: ArViewModel, projectId: String) {
        val owner = getPrivateField(vm, "nativeStateOwner") as NativeStateOwner
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!owner.owns(projectId) && System.nanoTime() < deadline) {
            Thread.sleep(10)
            testDispatcher.scheduler.advanceUntilIdle()
        }
        assertTrue("native state was never claimed for $projectId", owner.owns(projectId))
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
}
