package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Session
import kotlinx.coroutines.cancel
import com.hereliesaz.graffitixr.common.wearable.WearableManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.hereliesaz.graffitixr.common.util.isolateMarkings
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.feature.ar.anchor.FingerprintPartition
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.mockkObject
import io.mockk.mockkConstructor
import io.mockk.unmockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import com.hereliesaz.graffitixr.common.model.ArUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import com.hereliesaz.graffitixr.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val stereoProvider: StereoDepthProvider = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager = mockk(relaxed = true)
    private val collaborationManager: com.hereliesaz.graffitixr.core.collaboration.CollaborationManager = mockk(relaxed = true)
    private val wearableManager: WearableManager = mockk(relaxed = true)
    private val session: Session = mockk(relaxed = true)
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
        mockkStatic(Bitmap::class)
        mockkStatic(Canvas::class)
        mockkConstructor(Canvas::class)
        mockkStatic(Matrix::class)
        mockkStatic(Paint::class)
        mockkConstructor(Paint::class)
        val fakeBitmap = mockk<Bitmap>(relaxed = true)
        every { fakeBitmap.width } returns 100
        every { fakeBitmap.height } returns 100
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) } returns fakeBitmap
        every { Bitmap.createBitmap(any<Bitmap>(), any<Int>(), any<Int>(), any<Int>(), any<Int>(), any<android.graphics.Matrix>(), any<Boolean>()) } returns fakeBitmap
        mockkStatic("com.hereliesaz.graffitixr.common.util.ImageExtKt")
        // Match both the zero-arg call and the call with an explicit tapPos argument.
        every { any<Bitmap>().isolateMarkings() } returns fakeBitmap
        every { any<Bitmap>().isolateMarkings(any()) } returns fakeBitmap
        every { settingsRepository.ambientScanEnabled } returns flowOf(true)
        every { settingsRepository.isRightHanded } returns flowOf(true)
        every { settingsRepository.showAnchorBoundary } returns flowOf(false)
        every { settingsRepository.isImperialUnits } returns flowOf(false)
        every { projectRepository.currentProject } returns MutableStateFlow(null)
        every { context.filesDir } returns File("/tmp")
        viewModel = ArViewModel(slamManager, stereoProvider, projectRepository, settingsRepository, projectManager, collaborationManager, wearableManager, context, testDispatchers)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        // Drain real threads, then flush job-cleanup tasks queued on the test dispatcher before
        // resetMain(), preventing UncaughtExceptionsBeforeTest in subsequent tests.
        Thread.sleep(200)
        testDispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        unmockkStatic("com.hereliesaz.graffitixr.common.util.ImageExtKt")
        unmockkStatic(Bitmap::class)
        unmockkStatic(Canvas::class)
        unmockkConstructor(Canvas::class)
        unmockkStatic(Matrix::class)
        unmockkStatic(Paint::class)
        unmockkConstructor(Paint::class)
        unmockkObject(NativeLibLoader)
    }

    @Test
    fun `init loads AI models via slamManager`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        verify { slamManager.loadSuperPoint(any()) }
        verify { slamManager.loadLowLightEnhancer(any()) }
    }

    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.first()
        assertFalse(state.isScanning)
        assertFalse(state.isFlashlightOn)
    }

    @Test
    fun `toggleFlashlight updates flashlight state`() = runTest {
        assertFalse(viewModel.uiState.value.isFlashlightOn)
        viewModel.toggleFlashlight()
        assertTrue(viewModel.uiState.value.isFlashlightOn)
        viewModel.toggleFlashlight()
        assertFalse(viewModel.uiState.value.isFlashlightOn)
    }

    @Test
    fun `setTrackingState with true sets correct state`() = runTest {
        viewModel.setTrackingState(true, 0, 0, false)

        val state = viewModel.uiState.value
        assertTrue(state.isScanning)
    }

    @Test
    fun `setTrackingState with false sets correct state`() = runTest {
        viewModel.setTrackingState(false, 0, 0, false)

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
    }

    @Test
    fun `setTrackingState propagates isDepthApiSupported`() = runTest {
        viewModel.setTrackingState(true, 100, 0, true)
        assertTrue(viewModel.uiState.value.isDepthApiSupported)

        viewModel.setTrackingState(true, 100, 0, false)
        assertFalse(viewModel.uiState.value.isDepthApiSupported)
    }

    // Skipping ensureEngineInitialized test because SlamManager loads native library in init block
    // which causes UnsatisfiedLinkError in JVM unit tests.

    // ==================== Capture Workflow Tests ====================

    @Test
    fun `setTempCapture stores bitmap in state`() = runTest {
        val mockBitmap = mockk<Bitmap>(relaxed = true)

        assertNull(viewModel.uiState.value.tempCaptureBitmap)
        viewModel.setTempCapture(mockBitmap)

        assertEquals(mockBitmap, viewModel.uiState.value.tempCaptureBitmap)
    }

    @Test
    fun `onCaptureConsumed clears temp capture`() = runTest {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        viewModel.setTempCapture(mockBitmap)
        assertNotNull(viewModel.uiState.value.tempCaptureBitmap)

        viewModel.onCaptureConsumed()
        assertNull(viewModel.uiState.value.tempCaptureBitmap)
    }

    @Test
    fun `onCaptureConsumed clears the capture bitmap without recycling it`() = runTest {
        // Deliberately does NOT recycle: a background fingerprint/save coroutine may still hold
        // the same instance. The reference is dropped (GC reclaims); recycle() must not be called.
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.isRecycled } returns false
        viewModel.setTempCapture(mockBitmap)

        viewModel.onCaptureConsumed()

        verify(exactly = 0) { mockBitmap.recycle() }
        assertNull(viewModel.uiState.value.tempCaptureBitmap)
    }

    @Test
    fun `setUnwarpPoints updates state`() = runTest {
        val points = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f), Offset(0f, 100f))

        assertTrue(viewModel.uiState.value.unwarpPoints.isEmpty())
        viewModel.setUnwarpPoints(points)

        assertEquals(4, viewModel.uiState.value.unwarpPoints.size)
        assertEquals(points, viewModel.uiState.value.unwarpPoints)
    }

    @Test
    fun `requestCapture sets flag`() = runTest {
        assertFalse(viewModel.uiState.value.isCaptureRequested)

        viewModel.requestCapture()
        assertTrue(viewModel.uiState.value.isCaptureRequested)
    }

    @Test
    fun `onCaptureRequestHandled clears isCaptureRequested flag`() = runTest {
        viewModel.requestCapture()
        assertTrue(viewModel.uiState.value.isCaptureRequested)

        viewModel.onCaptureRequestHandled()
        assertFalse(viewModel.uiState.value.isCaptureRequested)
    }

    @Test
    fun `onScreenTap triggers capture request and onTargetCaptured clears it`() = runTest {
        assertFalse(viewModel.uiState.value.isCaptureRequested)

        viewModel.onScreenTap(0.5f, 0.5f)
        assertTrue(viewModel.uiState.value.isCaptureRequested)

        // Simulate frame arrival for a tap (non-null depth buffer to trigger logic)
        val bmp = mockk<Bitmap>(relaxed = true).also { every { it.width } returns 100; every { it.height } returns 100 }
        viewModel.onTargetCaptured(
            bitmap = bmp,
            depthBuffer = java.nio.ByteBuffer.allocate(10),
            colorW = 100, colorH = 100,
            depthBufW = 100, depthBufH = 100, depthBufStride = 200,
            intrinsics = null,
            viewMatrix = FloatArray(16),
            displayRotation = 0,
            tapDistanceMeters = -1f
        )

        assertFalse(viewModel.uiState.value.isCaptureRequested)
        assertEquals(1, viewModel.uiState.value.tapMarks.size)
    }

    @Test
    fun `clearTapHighlights clears keypoints and bitmaps`() = runTest {
        viewModel.clearTapHighlights()
        val state = viewModel.uiState.value
        assertTrue(state.tapMarks.isEmpty())
        assertNull(state.annotatedCaptureBitmap)
        assertNull(state.tempCaptureBitmap)
    }

    // ==================== Session Lifecycle Tests ====================

    @Test
    fun `session resumes only when in AR mode and activity is resumed`() = runTest {
        setPrivateField(viewModel, "session", session)
        enableArCore()
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)

        // Enter AR mode, but activity is paused
        viewModel.setArMode(true, context)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)

        // Resume activity
        viewModel.onActivityResumed()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        verify { session.resume() }
    }

    @Test
    fun `session pauses when activity is paused or not in AR mode`() = runTest {
        setPrivateField(viewModel, "session", session)
        enableArCore()
        viewModel.setArMode(true, context)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onActivityResumed()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(getPrivateField(viewModel, "isSessionResumed") as Boolean)

        // Pause activity
        viewModel.onActivityPaused()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        verify { session.pause() }

        // Resume activity, then exit AR mode
        viewModel.onActivityResumed()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        viewModel.setArMode(false, context)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(getPrivateField(viewModel, "isSessionResumed") as Boolean)
        verify { session.pause() }
    }

    // ==================== ORB Annotation Tests ====================

    @Test
    fun `onTargetCaptured normal path annotates keypoints asynchronously`() = runTest {
        val rawBmp = mockk<Bitmap>(relaxed = true)
        every { slamManager.getKeypoints(any()) } returns emptyList()

        viewModel.onTargetCaptured(
            bitmap = rawBmp, depthBuffer = null,
            colorW = 100, colorH = 100,
            depthBufW = 0, depthBufH = 0, depthBufStride = 0,
            intrinsics = null, viewMatrix = FloatArray(16), displayRotation = 0, tapDistanceMeters = -1f
        )
        // withContext(Dispatchers.Default) runs on a real thread pool; give it time to finish,
        // then advance testDispatcher to process the resulting state-update continuation.
        testDispatcher.scheduler.advanceUntilIdle()
        Thread.sleep(1000)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.annotatedCaptureBitmap)
    }

    // ==================== Scan Mode Tests ====================

    /**
     * The setting reaches uiState through the repository flow, so the flow is what the test drives.
     * A fresh ArViewModel is constructed after re-stubbing because the one from @Before already
     * collected the default stub.
     */
    @Test
    fun `the ambient scan flag reaches uiState in both directions`() = runTest {
        val flow = MutableStateFlow(false)
        every { settingsRepository.ambientScanEnabled } returns flow
        val vm = ArViewModel(
            slamManager, stereoProvider, projectRepository, settingsRepository, projectManager,
            collaborationManager, wearableManager, context, testDispatchers,
        )
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.ambientScanEnabled)

        flow.value = true
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.ambientScanEnabled)

        flow.value = false
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.ambientScanEnabled)
    }

    @Test
    fun `setTrackingState false with isDepthApiSupported true reflects correctly`() = runTest {
        viewModel.setTrackingState(false, 0, 0, true)

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertTrue(state.isDepthApiSupported)
    }

    @Test
    fun `setTrackingState false with zero splats reflects correctly`() = runTest {
        viewModel.setTrackingState(false, 0, 0, true)

        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertEquals(0, state.splatCount)
    }

    @Test
    fun `onTargetCaptured tap path sets annotatedCaptureBitmap to isolated markings and populates unwarpPoints`() = runTest {
        val rawBmp = mockk<Bitmap>(relaxed = true)
        viewModel.onScreenTap(0.5f, 0.5f)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onTargetCaptured(
            bitmap = rawBmp, depthBuffer = null,
            colorW = 100, colorH = 100,
            depthBufW = 0, depthBufH = 0, depthBufStride = 0,
            intrinsics = null, viewMatrix = FloatArray(16), displayRotation = 0, tapDistanceMeters = -1f
        )

        val state = viewModel.uiState.value
        // The tap path isolates markings on the raw capture; onUnwarpConfirm later reads this mask
        // and unwarps it, so it MUST be non-null here (nulling it loses the mask through RECTIFY).
        assertNotNull(state.annotatedCaptureBitmap)
        assertEquals(4, state.unwarpPoints.size)
    }

    // ==================== First-run walkthrough (doodle) tests ====================

    @Test
    fun `doodle detect phase ends itself when ARCore never establishes an anchor`() = runTest {
        // The room never yields a trackable surface, so isAnchorEstablished stays false and the detect
        // budget is never armed. The phase must still terminate: the marks the user drew are only a
        // teaching aid for the sixty-second walkthrough, and an unbounded phase would leave onboarding
        // running forever — the tutorial never marked complete, doodleLockActive latched on.
        assertFalse(viewModel.uiState.value.isAnchorEstablished)

        viewModel.setDoodlePhase(true)
        testDispatcher.scheduler.advanceTimeBy(ArViewModel.DOODLE_PHASE_TIMEOUT_MS + 1_000L)
        testDispatcher.scheduler.runCurrent()

        assertTrue(
            "detect phase must self-terminate without an anchor",
            viewModel.uiState.value.doodleLocked,
        )
    }

    @Test
    fun `doodle detect phase does not end early while it still has budget`() = runTest {
        viewModel.setDoodlePhase(true)
        // Well past a couple of capture intervals but far short of the phase cap.
        testDispatcher.scheduler.advanceTimeBy(ArViewModel.DOODLE_CAPTURE_INTERVAL_MS * 3)
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.doodleLocked)
    }

    @Test
    fun `setDoodlePhase false cancels the capture loop`() = runTest {
        viewModel.setDoodlePhase(true)
        testDispatcher.scheduler.advanceTimeBy(ArViewModel.DOODLE_CAPTURE_INTERVAL_MS * 2)
        testDispatcher.scheduler.runCurrent()

        viewModel.setDoodlePhase(false)
        // Past the cap: a cancelled loop must not fire the completion signal afterwards.
        testDispatcher.scheduler.advanceTimeBy(ArViewModel.DOODLE_PHASE_TIMEOUT_MS * 2)
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.doodleLocked)
    }

    // ==================== Fingerprint load / partition cascade ====================

    /**
     * A fingerprint whose points straddle a 0.5 x 0.5 m design placed at the anchor: three under it,
     * three well outside. The absolute positions only have to survive Φ's classification; what the
     * tests below care about is that the partition is non-trivial, so an "already partitioned"
     * fixture cannot be confused with an empty one.
     */
    private val fixturePoints = listOf(
        0f, 0f, 0f, 0.1f, 0.1f, 0f, -0.2f, 0.05f, 0f,
        1.0f, 0f, 0f, 0f, -1.2f, 0f, -0.9f, 0.8f, 0f,
    )

    private val fixtureFootprint = FingerprintPartition.DesignFootprint(
        rigidModelAnchorLocal = identityMatrix(),
        halfW = 0.5f,
        halfH = 0.5f,
    )

    /** Unpartitioned: `regions` empty, extents unknown. The state a fresh capture is saved in. */
    private fun unpartitionedFingerprint() = com.hereliesaz.graffitixr.common.model.Fingerprint(
        keypoints = emptyList(),
        points3d = fixturePoints,
        descriptorsData = ByteArray(fixturePoints.size / 3 * 32),
        descriptorsRows = fixturePoints.size / 3,
        descriptorsCols = 32,
        descriptorsType = 0,
        // Non-negative so `isLegacyFrame` is false and the pre-Phase-0 refusal doesn't short-circuit
        // the restore this test is measuring.
        captureRotationDeg = 0,
        captureAnchorCam = identityMatrix().toList(),
    )

    private fun projectWith(fp: com.hereliesaz.graffitixr.common.model.Fingerprint) =
        com.hereliesaz.graffitixr.common.model.GraffitiProject(
            id = "partition-fixture",
            fingerprint = fp,
            // The metric restore is gated on both being present; without them the load takes the
            // descriptors-only legacy branch and never reaches the partition at all.
            fingerprintIntrinsics = listOf(500f, 500f, 320f, 240f),
            fingerprintAnchor = identityMatrix().toList(),
            fingerprintCaptureRotationDeg = 0,
        )

    private fun identityMatrix() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
    )

    /**
     * `loadFingerprintIfExists` and the partition both run off the test dispatcher — IO and Default
     * respectively — so the sequence load → partition → (possible) write only completes on real
     * threads. Interleave scheduler drains with real pauses until it has.
     */
    private fun settle(rounds: Int = 6, pauseMs: Long = 150L) {
        repeat(rounds) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(pauseMs)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun viewModelOn(projects: MutableStateFlow<com.hereliesaz.graffitixr.common.model.GraffitiProject?>): ArViewModel {
        every { projectRepository.currentProject } returns projects
        return ArViewModel(
            slamManager, stereoProvider, projectRepository, settingsRepository, projectManager,
            collaborationManager, wearableManager, context, testDispatchers,
        )
    }

    /**
     * Loading an already-correctly-partitioned fingerprint must push it to native once and write
     * nothing.
     *
     * `loadFingerprintIfExists` runs on EVERY `currentProject` emission and the partition itself
     * calls `updateProject`, so a partition that writes on load is a cycle: load → partition →
     * write → emission → load. Before the idempotence check in `onDesignFootprintChanged` that is
     * exactly what happened — two partitions, two `restoreWallFingerprintMetric` calls (each
     * resetting native's reloc state) and two full project writes per load. It terminated only
     * because `Fingerprint.equals` compares `regions` by content and `MutableStateFlow` conflates
     * equal values, which is an accident of an override written for a serialization test: one
     * non-value-equal field added to `Fingerprint` turns it into an unbounded write loop.
     *
     * The stored regions come from `FingerprintPartition.partition` itself rather than a literal, so
     * the fixture cannot drift away from what the implementation would compute.
     */
    @Test
    fun `loading an already-partitioned fingerprint pushes once and writes nothing`() = runTest {
        val stored = FingerprintPartition.partition(unpartitionedFingerprint(), fixtureFootprint)
        assertNotEquals(
            "fixture must actually be partitioned, or this test proves nothing",
            0, stored.regions.size,
        )
        val projects = MutableStateFlow<com.hereliesaz.graffitixr.common.model.GraffitiProject?>(null)
        val vm = viewModelOn(projects)
        // The footprint edge fires before the fingerprint arrives — the ordering a project load
        // always has, since the artwork is placed from the saved project.
        vm.onDesignFootprintChanged(fixtureFootprint)

        projects.value = projectWith(stored)
        settle()

        verify(exactly = 1) {
            slamManager.restoreWallFingerprintMetric(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
        coVerify(exactly = 0) { projectRepository.updateProject(any<com.hereliesaz.graffitixr.common.model.GraffitiProject>()) }
        coVerify(exactly = 0) {
            projectRepository.updateProject(any<(com.hereliesaz.graffitixr.common.model.GraffitiProject) -> com.hereliesaz.graffitixr.common.model.GraffitiProject>())
        }
    }

    /**
     * The other half, without which the test above would also pass if partitioning were broken
     * outright: an UNpartitioned fingerprint must be partitioned and persisted — once.
     */
    @Test
    fun `loading an unpartitioned fingerprint partitions it and writes exactly once`() = runTest {
        val projects = MutableStateFlow<com.hereliesaz.graffitixr.common.model.GraffitiProject?>(null)
        val vm = viewModelOn(projects)
        vm.onDesignFootprintChanged(fixtureFootprint)

        projects.value = projectWith(unpartitionedFingerprint())
        settle()

        coVerify(exactly = 1) {
            projectRepository.updateProject(any<(com.hereliesaz.graffitixr.common.model.GraffitiProject) -> com.hereliesaz.graffitixr.common.model.GraffitiProject>())
        }
        // The restore on load, plus the one that hands native the freshly partitioned regions.
        verify(exactly = 2) {
            slamManager.restoreWallFingerprintMetric(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        }
    }


    /**
     * Attaching a renderer re-asserts the standing self-grow intent on the engine.
     *
     * Exactly twice: once from the toggle itself, once from the attach. One call would mean the
     * attach did not re-apply it, which is the whole point.
     *
     * ## Why the fusion half of this fix has no test
     *
     * `attachSessionToRenderer` also pushes the fusion intent onto the renderer, and that is the
     * half that actually fixes a user-visible desync — `ArRenderer` is rebuilt when the AR view is,
     * so a new one starts at its shipped `fusionEnabled = false` while the toggle still reads ON.
     *
     * It is not covered because `ArRenderer` cannot be mocked in this harness: `mockk(relaxed = true)`
     * does not intercept it, so `attachSession` runs its real body and dereferences a `sessionLock`
     * that a constructor-less mock never initialised. Every route to the assertion goes through that
     * call. Passing `null` — as this test does — exercises the engine write but skips the renderer
     * write entirely, since it is behind `renderer?.`.
     *
     * So the fusion re-apply is verified by reading, not by a test, and this note exists so nobody
     * later mistakes the presence of a nearby green test for coverage of it.
     */
    @Test
    fun `attaching re-applies the self-grow intent`() = runTest {
        viewModel.evalSetSelfGrowEnabled(true)
        viewModel.attachSessionToRenderer(null)
        verify(exactly = 2) { slamManager.setSelfGrowEnabled(true) }
    }

    /**
     * With no renderer attached there is nothing to switch, and the toggle must say so rather than
     * report a state it failed to apply.
     *
     * This is the read-back discipline paying for itself: `evalSetFusionEnabled` writes to the
     * renderer and then reports whatever the renderer holds, so a null renderer yields OFF instead
     * of a cheerful ON with nothing behind it.
     */
    @Test
    fun `enabling fusion with no renderer reports off rather than a state it could not apply`() = runTest {
        viewModel.evalSetFusionEnabled(true)
        assertFalse(viewModel.evalFusionEnabled.value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun enableArCore() {
        // setArMode(true) early-returns unless ARCore is reported available; flip the flag on the
        // private state flow so the lifecycle tests can actually enter AR mode.
        val field = viewModel.javaClass.getDeclaredField("_uiState")
        field.isAccessible = true
        val flow = field.get(viewModel) as MutableStateFlow<ArUiState>
        flow.value = flow.value.copy(isArCoreAvailable = true)
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
