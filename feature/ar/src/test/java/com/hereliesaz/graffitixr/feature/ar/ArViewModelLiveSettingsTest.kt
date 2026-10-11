package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.util.Range
import androidx.lifecycle.viewModelScope
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.CameraTargetFps
import com.hereliesaz.graffitixr.common.model.CoverageGlowColor
import com.hereliesaz.graffitixr.common.model.CoverageGlowIntensity
import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import com.hereliesaz.graffitixr.common.model.SphereSlamRuntimeMode
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.sphereslam.SphereSlam
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Settings toggles are enacted immediately: "Use ARCore" re-derives the AR backend on the spot, and
 * the session-level settings reconfigure a live ARCore session instead of waiting for the next AR
 * entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelLiveSettingsTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager = mockk(relaxed = true)
    private val collaborationManager: com.hereliesaz.graffitixr.core.collaboration.CollaborationManager =
        mockk(relaxed = true)
    private val wearableManager: WearableManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    private val arCoreEnabled = MutableStateFlow(true)
    private val arCoreDepthEnabled = MutableStateFlow(true)
    private val cameraTargetFps = MutableStateFlow(CameraTargetFps.DEVICE_DEFAULT)
    private var viewModel: ArViewModel? = null

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        mockkObject(SphereSlam)
        every { SphereSlam.isOperational() } returns true
        mockkObject(ArAvailabilityChecker)
        every { settingsRepository.ambientScanEnabled } returns flowOf(true)
        every { settingsRepository.isRightHanded } returns flowOf(true)
        every { settingsRepository.showAnchorBoundary } returns flowOf(false)
        every { settingsRepository.isImperialUnits } returns flowOf(false)
        // Off, so no LiteRT model load is attempted in the test JVM (see ArViewModelTest).
        every { settingsRepository.monocularDepthEnabled } returns flowOf(false)
        every { settingsRepository.arCoreEnabled } returns arCoreEnabled
        every { settingsRepository.arCoreDepthEnabled } returns arCoreDepthEnabled
        every { settingsRepository.cameraTargetFps } returns cameraTargetFps
        every { settingsRepository.adaptiveRateEnabled } returns flowOf(true)
        every { projectRepository.currentProject } returns MutableStateFlow(null)
        every { context.filesDir } returns File("/tmp")
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

    private fun newViewModel(checkResult: ArAvailabilityChecker.Result): ArViewModel {
        coEvery { ArAvailabilityChecker.check(any()) } returns checkResult
        return ArViewModel(
            slamManager, projectRepository, settingsRepository, projectManager,
            collaborationManager, wearableManager, context, testDispatchers,
        ).also { viewModel = it }
    }

    @Test
    fun `turning Use ARCore off and on switches the backend immediately`() {
        val vm = newViewModel(ArAvailabilityChecker.Result.Supported)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.isArCoreAvailabilityResolved)
        assertTrue(vm.uiState.value.isArCoreAvailable)
        assertEquals(SphereSlamRuntimeMode.ARCORE_SIDECAR, vm.uiState.value.sphereSlamRuntimeStatus.mode)

        arCoreEnabled.value = false
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.arCoreEnabled)
        assertFalse(vm.uiState.value.isArCoreAvailable)
        assertTrue(vm.uiState.value.isArCoreAvailabilityResolved)
        assertEquals(SphereSlamRuntimeMode.STANDALONE, vm.uiState.value.sphereSlamRuntimeStatus.mode)
        // Same native cleanup the startup path runs for an unsupported device.
        verify { slamManager.clearWallFingerprint() }
        verify { slamManager.clearWallFeatureMap() }

        arCoreEnabled.value = true
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.isArCoreAvailable)
        assertEquals(SphereSlamRuntimeMode.ARCORE_SIDECAR, vm.uiState.value.sphereSlamRuntimeStatus.mode)
        // The checker's verdict is cached; a toggle never re-runs it.
        coVerify(exactly = 1) { ArAvailabilityChecker.check(any()) }
    }

    @Test
    fun `Use ARCore on cannot select ARCore on an unsupported device`() {
        arCoreEnabled.value = false
        val vm = newViewModel(ArAvailabilityChecker.Result.Unsupported)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.isArCoreAvailable)

        arCoreEnabled.value = true
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.arCoreEnabled)
        assertFalse(vm.uiState.value.isArCoreAvailable)
        assertEquals(SphereSlamRuntimeMode.STANDALONE, vm.uiState.value.sphereSlamRuntimeStatus.mode)
    }

    @Test
    fun `turning ARCore depth off reconfigures the live session`() {
        val vm = newViewModel(ArAvailabilityChecker.Result.Supported)
        val liveConfig: Config = mockk(relaxed = true)
        val session: Session = mockk(relaxed = true)
        every { session.config } returns liveConfig
        every { session.isDepthModeSupported(any()) } returns true
        setPrivateField(vm, "session", session)
        setPrivateField(vm, "appliedDepthRequested", true)
        setPrivateField(vm, "appliedCameraFps", CameraTargetFps.DEVICE_DEFAULT)
        testDispatcher.scheduler.runCurrent()
        // The collector's initial emission matches what the session was built with: untouched.
        verify(exactly = 0) { session.configure(any()) }

        arCoreDepthEnabled.value = false
        testDispatcher.scheduler.runCurrent()

        verify { liveConfig.depthMode = Config.DepthMode.DISABLED }
        verify(exactly = 1) { session.configure(liveConfig) }
        // Depth is a config-level change: no camera reopen.
        verify(exactly = 0) { session.pause() }
        assertFalse(vm.uiState.value.isDepthApiSupported)
        assertEquals(false, getPrivateField(vm, "appliedDepthRequested"))
    }

    @Test
    fun `changing camera fps swaps the live session camera config`() {
        val vm = newViewModel(ArAvailabilityChecker.Result.Supported)
        val liveConfig: Config = mockk(relaxed = true)
        val defaultCameraConfig: CameraConfig = mockk(relaxed = true)
        val fpsRange: Range<Int> = mockk(relaxed = true)
        every { fpsRange.upper } returns 30
        every { defaultCameraConfig.fpsRange } returns fpsRange
        val session: Session = mockk(relaxed = true)
        every { session.config } returns liveConfig
        setPrivateField(vm, "session", session)
        setPrivateField(vm, "isSessionResumed", true)
        setPrivateField(vm, "isActivityResumed", true)
        setPrivateField(vm, "isInArMode", true)
        setPrivateField(vm, "appliedDepthRequested", true)
        setPrivateField(vm, "appliedCameraFps", CameraTargetFps.FPS_30)
        setPrivateField(vm, "sessionDefaultCameraConfig", defaultCameraConfig)
        cameraTargetFps.value = CameraTargetFps.FPS_30
        testDispatcher.scheduler.runCurrent()
        verify(exactly = 0) { session.pause() }

        // Back to ARCore's own pick: needs no filter query, just the default config restored.
        cameraTargetFps.value = CameraTargetFps.DEVICE_DEFAULT
        testDispatcher.scheduler.runCurrent()

        verifyOrder {
            session.pause()
            session.cameraConfig = defaultCameraConfig
            session.configure(liveConfig)
            session.resume()
        }
        assertEquals(CameraTargetFps.DEVICE_DEFAULT, getPrivateField(vm, "appliedCameraFps"))
        assertTrue(getPrivateField(vm, "isSessionResumed") as Boolean)
    }

    @Test
    fun `fps change without a session is left for session build`() {
        newViewModel(ArAvailabilityChecker.Result.Supported)
        testDispatcher.scheduler.runCurrent()
        cameraTargetFps.value = CameraTargetFps.FPS_60
        testDispatcher.scheduler.runCurrent()
        // Nothing to reconfigure; initArSessionLocked reads the new value when AR is entered.
        assertEquals(CameraTargetFps.FPS_60, viewModel!!.uiState.value.cameraTargetFps)
    }

    @Test
    fun `coverage glow settings apply to ui state at once and are persisted`() {
        val vm = newViewModel(ArAvailabilityChecker.Result.Supported)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setCoverageGlowStyle(CoverageGlowStyle.MISSING_TILES)
        vm.setCoverageGlowColor(CoverageGlowColor.CYAN.argb)
        // Immediate, before any coroutine runs: the live glow restyles without waiting on DataStore.
        assertEquals(CoverageGlowStyle.MISSING_TILES, vm.uiState.value.coverageGlow.style)
        assertEquals(CoverageGlowColor.CYAN.argb, vm.uiState.value.coverageGlow.colorArgb)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { settingsRepository.setCoverageGlowStyle(CoverageGlowStyle.MISSING_TILES) }
        coVerify { settingsRepository.setCoverageGlowColor(CoverageGlowColor.CYAN.argb) }
    }

    @Test
    fun `coverage glow intensity drags are live but persisted once, clamped`() {
        val vm = newViewModel(ArAvailabilityChecker.Result.Supported)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setCoverageGlowIntensity(0.2f)
        vm.setCoverageGlowIntensity(0.3f)
        vm.setCoverageGlowIntensity(0.9f) // past the slider's top
        assertEquals(CoverageGlowIntensity.MAX, vm.uiState.value.coverageGlow.intensity, 0f)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { settingsRepository.setCoverageGlowIntensity(any()) }
        coVerify { settingsRepository.setCoverageGlowIntensity(CoverageGlowIntensity.MAX) }
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
